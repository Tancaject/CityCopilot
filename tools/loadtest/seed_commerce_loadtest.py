#!/usr/bin/env python3
"""Seed and verify the isolated Commerce seckill path without extra Python packages."""

from __future__ import annotations

import argparse
import csv
import json
import socket
import subprocess
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOKENS = Path(__file__).with_name("commerce-tokens.csv")
VOUCHER_ID = 200
START_USER_ID = 200_000
STREAM = "commerce:stream:orders"
CONSUMER_GROUP = "commerce-order-workers"


def env_file() -> dict[str, str]:
    values: dict[str, str] = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key] = value
    return values


def mysql(sql: str) -> list[str]:
    command = [
        "docker", "compose", "exec", "-T", "commerce-mysql", "sh", "-c",
        'mysql -N -B -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"',
    ]
    completed = subprocess.run(
        command, cwd=ROOT, input=sql, text=True, encoding="utf-8",
        capture_output=True,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(f"Commerce MySQL command failed: {detail}")
    return [line for line in completed.stdout.splitlines() if line]


def encode(*parts: object) -> bytes:
    chunks = [f"*{len(parts)}\r\n".encode()]
    for part in parts:
        raw = str(part).encode("utf-8")
        chunks.append(f"${len(raw)}\r\n".encode() + raw + b"\r\n")
    return b"".join(chunks)


def redis_pipeline(commands: list[tuple[object, ...]], password: str, port: int) -> None:
    with socket.create_connection(("127.0.0.1", port), timeout=10) as connection:
        connection.sendall(encode("AUTH", password) + b"".join(encode(*command) for command in commands))
        reader = connection.makefile("rb")
        for _ in range(len(commands) + 1):
            line = reader.readline()
            if not line or line.startswith(b"-"):
                raise RuntimeError(line.decode("utf-8", errors="replace").strip())
            if line.startswith(b"$"):
                length = int(line[1:])
                if length >= 0:
                    reader.read(length + 2)
            elif line.startswith(b"*"):
                raise RuntimeError("Unexpected aggregate Redis response")


def redis_cli(*arguments: str) -> list[str]:
    command = [
        "docker", "compose", "exec", "-T", "redis", "sh", "-c",
        'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --raw "$@"',
        "redis-cli",
        *arguments,
    ]
    completed = subprocess.run(
        command, cwd=ROOT, text=True, encoding="utf-8",
        capture_output=True,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(f"Redis command failed: {detail}")
    return completed.stdout.splitlines()


def stream_status() -> dict[str, object]:
    pending_lines = redis_cli("XPENDING", STREAM, CONSUMER_GROUP)
    group_lines = redis_cli("XINFO", "GROUPS", STREAM)
    last_entry_lines = redis_cli("XREVRANGE", STREAM, "+", "-", "COUNT", "1")
    pending = int(pending_lines[0]) if pending_lines and pending_lines[0] else 0
    group = dict(zip(group_lines[0::2], group_lines[1::2]))
    lag_text = group.get("lag", "")
    lag = int(lag_text) if lag_text else None
    stream_tail_id = last_entry_lines[0] if last_entry_lines else None
    last_delivered_id = group.get("last-delivered-id")
    delivered_to_tail = bool(last_delivered_id and last_delivered_id == stream_tail_id)
    return {
        "pending": pending,
        "lag": lag,
        "lastDeliveredId": last_delivered_id,
        "streamTailId": stream_tail_id,
        "drained": pending == 0 and (lag == 0 or delivered_to_tail),
    }


def wait_for_stream_drain(timeout_seconds: int) -> None:
    deadline = time.monotonic() + timeout_seconds
    while True:
        status = stream_status()
        if status["drained"]:
            return
        if time.monotonic() >= deadline:
            raise RuntimeError(
                "Previous seckill stream has not drained: "
                f"pending={status['pending']}, lag={status['lag']}"
            )
        time.sleep(2)


def seed(stock: int, users: int, drain_timeout_seconds: int) -> None:
    wait_for_stream_drain(drain_timeout_seconds)
    mysql(f"""
DELETE FROM commerce_dead_letter
WHERE JSON_VALID(payload_json)
AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.voucherId'))=CAST({VOUCHER_ID} AS CHAR);
DELETE FROM commerce_outbox_event
WHERE (aggregate_type='commerce_order'
       AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM commerce_order WHERE voucher_id={VOUCHER_ID}))
   OR (aggregate_type='commerce_seckill_voucher' AND aggregate_id=CAST({VOUCHER_ID} AS CHAR));
DELETE FROM commerce_reservation WHERE voucher_id={VOUCHER_ID};
DELETE FROM commerce_order WHERE voucher_id={VOUCHER_ID};
INSERT INTO shop_summary_projection(shop_id,shop_name,owner_user_id,source_version)
VALUES (1,'Commerce Load Test Shop',1,1)
ON DUPLICATE KEY UPDATE shop_name=VALUES(shop_name);
INSERT INTO commerce_voucher(id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status)
VALUES ({VOUCHER_ID},1,'Commerce Load Test','reproducible fixture','one per user',1,100,1,1)
ON DUPLICATE KEY UPDATE status=1,type=1;
INSERT INTO commerce_seckill_voucher(voucher_id,stock,begin_time,end_time)
VALUES ({VOUCHER_ID},{stock},CURRENT_TIMESTAMP(3)-INTERVAL 1 DAY,CURRENT_TIMESTAMP(3)+INTERVAL 1 DAY)
ON DUPLICATE KEY UPDATE stock={stock},begin_time=VALUES(begin_time),end_time=VALUES(end_time);
""")
    env = env_file()
    previous_tokens = []
    if TOKENS.exists():
        with TOKENS.open(newline="", encoding="utf-8") as handle:
            previous_tokens = [row[0] for row in csv.reader(handle) if row]
    tokens = [uuid.uuid4().hex for _ in range(users)]
    commands: list[tuple[object, ...]] = [
        *(("DEL", f"login:token:{token}") for token in previous_tokens),
        ("DEL", f"commerce:seckill:stock:{VOUCHER_ID}"),
        ("DEL", f"commerce:seckill:buyers:{VOUCHER_ID}"),
        ("SET", f"commerce:seckill:stock:{VOUCHER_ID}", stock),
    ]
    for index, token in enumerate(tokens):
        user_id = START_USER_ID + index
        commands.extend([
            ("HSET", f"login:token:{token}", "id", user_id, "nickName", f"commerce_lt_{user_id}", "role", "user"),
            ("EXPIRE", f"login:token:{token}", 7200),
        ])
    redis_pipeline(commands, env["CITYCOPILOT_REDIS_PASSWORD"], int(env.get("CITYCOPILOT_REDIS_PORT", "6380")))
    with TOKENS.open("w", newline="", encoding="utf-8") as handle:
        csv.writer(handle).writerows([[token] for token in tokens])
    print(json.dumps({
        "voucherId": VOUCHER_ID,
        "stock": stock,
        "users": users,
        "previousTokensRemoved": len(previous_tokens),
        "tokens": str(TOKENS),
    }, indent=2))


def verify(
        initial_stock: int,
        allow_unpublished: bool = False,
        expected_orders: int | None = None,
) -> int:
    rows = mysql(f"""
SELECT COUNT(*),COUNT(DISTINCT user_id),COALESCE(MIN(user_id),0),COALESCE(MAX(user_id),0)
FROM commerce_order WHERE voucher_id={VOUCHER_ID};
SELECT stock FROM commerce_seckill_voucher WHERE voucher_id={VOUCHER_ID};
SELECT COUNT(*) FROM commerce_outbox_event WHERE aggregate_type='commerce_order'
AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM commerce_order WHERE voucher_id={VOUCHER_ID})
AND status <> 'PUBLISHED';
SELECT COUNT(*) FROM commerce_dead_letter
WHERE JSON_VALID(payload_json)
AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.voucherId'))=CAST({VOUCHER_ID} AS CHAR);
SELECT COUNT(*),COALESCE(SUM(status='ORDER_CREATED'),0)
FROM commerce_reservation WHERE voucher_id={VOUCHER_ID};
""")
    order_count, distinct_users, minimum_user, maximum_user = map(int, rows[0].split("\t"))
    database_stock = int(rows[1])
    unpublished = int(rows[2])
    dead_letters = int(rows[3])
    reservation_count, order_created_reservations = map(int, rows[4].split("\t"))
    env = env_file()
    # Read only the Commerce keys with the administrative test harness account.
    with socket.create_connection(("127.0.0.1", int(env.get("CITYCOPILOT_REDIS_PORT", "6380"))), timeout=10) as connection:
        connection.sendall(
            encode("AUTH", env["CITYCOPILOT_REDIS_PASSWORD"])
            + encode("GET", f"commerce:seckill:stock:{VOUCHER_ID}")
            + encode("SCARD", f"commerce:seckill:buyers:{VOUCHER_ID}")
        )
        reader = connection.makefile("rb")
        if not reader.readline().startswith(b"+OK"):
            raise RuntimeError("Redis authentication failed")
        length = int(reader.readline()[1:])
        redis_stock = int(reader.read(length).decode())
        reader.read(2)
        buyers_reply = reader.readline()
        if not buyers_reply.startswith(b":"):
            raise RuntimeError("Unexpected Redis SCARD response")
        redis_buyers = int(buyers_reply[1:])
    stream = stream_status()
    report = {
        "voucherId": VOUCHER_ID,
        "orders": order_count,
        "distinctUsers": distinct_users,
        "userRange": [minimum_user, maximum_user],
        "reservations": reservation_count,
        "orderCreatedReservations": order_created_reservations,
        "databaseStock": database_stock,
        "redisStock": redis_stock,
        "redisBuyerCount": redis_buyers,
        "streamPending": stream["pending"],
        "streamLag": stream["lag"],
        "streamDrained": stream["drained"],
        "unpublishedOutbox": unpublished,
        "deadLetters": dead_letters,
        "expectedOrders": expected_orders,
        "ordersMatchExpected": expected_orders is None or order_count == expected_orders,
        "oversold": order_count > initial_stock,
        "duplicateBusinessEffects": order_count - distinct_users,
        "consistent": database_stock == redis_stock == initial_stock - order_count,
    }
    print(json.dumps(report, indent=2))
    outbox_valid = allow_unpublished or unpublished == 0
    valid = (
        not report["oversold"]
        and report["duplicateBusinessEffects"] == 0
        and report["consistent"]
        and report["ordersMatchExpected"]
        and redis_buyers == order_count
        and reservation_count == order_count
        and order_created_reservations == order_count
        and report["streamDrained"]
        and dead_letters == 0
        and outbox_valid
    )
    return 0 if valid else 2


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stock", type=int, default=300)
    parser.add_argument("--users", type=int, default=300)
    parser.add_argument("--drain-timeout", type=int, default=600)
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--wait-for-drain", action="store_true")
    parser.add_argument("--expected-orders", type=int)
    parser.add_argument(
        "--allow-unpublished",
        action="store_true",
        help="Do not fail verification when the local RocketMQ relay is intentionally disabled.",
    )
    args = parser.parse_args()
    if args.stock < 0:
        parser.error("--stock must be non-negative")
    if args.users <= 0:
        parser.error("--users must be positive")
    if args.expected_orders is not None and not 0 <= args.expected_orders <= args.stock:
        parser.error("--expected-orders must be between zero and --stock")
    if args.verify:
        if args.wait_for_drain:
            wait_for_stream_drain(args.drain_timeout)
        return verify(args.stock, args.allow_unpublished, args.expected_orders)
    seed(args.stock, args.users, args.drain_timeout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
