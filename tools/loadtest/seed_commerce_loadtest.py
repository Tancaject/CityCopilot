#!/usr/bin/env python3
"""Seed and verify the isolated Commerce seckill path without extra Python packages."""

from __future__ import annotations

import argparse
import csv
import json
import re
import socket
import subprocess
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOKENS = Path(__file__).with_name("commerce-tokens.csv")
VOUCHER_ID = 200
START_USER_ID = 200_000
CONSUMER_GROUP = "commerce-order-workers"
SAFE_GENERATION = re.compile(r"^[A-Za-z0-9_-]{1,24}$")


def java_long_hash(value: int) -> int:
    hashed = (value ^ (value >> 32)) & 0xFFFFFFFF
    return hashed - 0x100000000 if hashed >= 0x80000000 else hashed


def physical_shard(voucher_id: int, shard_count: int) -> int:
    return java_long_hash(voucher_id) % shard_count


def shard_prefix(generation: str, shard_count: int, shard: int) -> str:
    return f"commerce:{{commerce:{generation}:{shard_count}:{shard}}}:"


def voucher_prefix(generation: str, shard_count: int, voucher_id: int) -> str:
    return shard_prefix(generation, shard_count, physical_shard(voucher_id, shard_count))


def stream_keys(generation: str, shard_count: int) -> list[str]:
    return [
        f"{shard_prefix(generation, shard_count, shard)}stream:orders"
        for shard in range(shard_count)
    ]


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
    lines = completed.stdout.splitlines()
    if lines and lines[0].startswith(("ERR", "NOGROUP", "NOAUTH", "NOPERM", "WRONGTYPE")):
        raise RuntimeError(f"Redis command failed: {lines[0]}")
    return lines



def one_stream_status(stream: str) -> dict[str, object]:
    exists_lines = redis_cli("EXISTS", stream)
    if not exists_lines or int(exists_lines[0]) == 0:
        return {"stream": stream, "pending": 0, "lag": 0, "drained": True}
    length = int(redis_cli("XLEN", stream)[0])
    try:
        pending_lines = redis_cli("XPENDING", stream, CONSUMER_GROUP)
    except RuntimeError:
        if length == 0:
            return {"stream": stream, "pending": 0, "lag": 0, "drained": True}
        raise
    group_lines = redis_cli("XINFO", "GROUPS", stream)
    last_entry_lines = redis_cli("XREVRANGE", stream, "+", "-", "COUNT", "1")
    pending = int(pending_lines[0]) if pending_lines and pending_lines[0] else 0
    selected: dict[str, str] = {}
    current_group = ""
    for field, value in zip(group_lines[0::2], group_lines[1::2]):
        if field == "name":
            current_group = value
        if current_group == CONSUMER_GROUP:
            selected[field] = value
    if not selected:
        raise RuntimeError(f"Consumer group {CONSUMER_GROUP} is missing for {stream}")
    lag_text = selected.get("lag", "")
    lag = int(lag_text) if lag_text else None
    stream_tail_id = last_entry_lines[0] if last_entry_lines else None
    last_delivered_id = selected.get("last-delivered-id")
    delivered_to_tail = bool(last_delivered_id and last_delivered_id == stream_tail_id)
    return {
        "stream": stream,
        "pending": pending,
        "lag": lag,
        "lastDeliveredId": last_delivered_id,
        "streamTailId": stream_tail_id,
        "drained": pending == 0 and (lag == 0 or delivered_to_tail),
    }


def stream_status(generation: str, shard_count: int) -> dict[str, object]:
    shards = [one_stream_status(stream) for stream in stream_keys(generation, shard_count)]
    return {
        "pending": sum(int(shard["pending"]) for shard in shards),
        "lag": sum(int(shard["lag"] or 0) for shard in shards),
        "drained": all(bool(shard["drained"]) for shard in shards),
        "shards": shards,
    }


def wait_for_stream_drain(
        timeout_seconds: int,
        generation: str,
        shard_count: int,
) -> None:
    deadline = time.monotonic() + timeout_seconds
    while True:
        status = stream_status(generation, shard_count)
        if status["drained"]:
            return
        if time.monotonic() >= deadline:
            raise RuntimeError(
                "Previous seckill stream has not drained: "
                f"generation={generation}, shards={shard_count}, "
                f"pending={status['pending']}, lag={status['lag']}"
            )
        time.sleep(2)


def delete_matching_keys(pattern: str, predicate=None) -> int:
    keys = [
        key for key in redis_cli("--scan", "--pattern", pattern)
        if key and (predicate is None or predicate(key))
    ]
    for offset in range(0, len(keys), 500):
        redis_cli("DEL", *keys[offset:offset + 500])
    return len(keys)


def belongs_to_load_user(key: str, users: int) -> bool:
    try:
        user_id = int(key.split("}:", 1)[1].split(":", 1)[0])
    except (IndexError, ValueError):
        return False
    return START_USER_ID <= user_id < START_USER_ID + users


def belongs_to_voucher_load_user(key: str, users: int) -> bool:
    marker = f"seckill:idempotency:{VOUCHER_ID}:"
    try:
        user_id = int(key.split(marker, 1)[1].split(":", 1)[0])
    except (IndexError, ValueError):
        return False
    return START_USER_ID <= user_id < START_USER_ID + users


def seed(
        stock: int,
        users: int,
        drain_timeout_seconds: int,
        generation: str,
        shard_count: int,
) -> None:
    wait_for_stream_drain(drain_timeout_seconds, generation, shard_count)
    mysql(f"""
DELETE FROM commerce_dead_letter
WHERE JSON_VALID(payload_json)
AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.voucherId'))=CAST({VOUCHER_ID} AS CHAR);
DELETE FROM commerce_admission_ledger
WHERE voucher_id={VOUCHER_ID}
AND user_id>={START_USER_ID} AND user_id<{START_USER_ID + users};
DELETE FROM commerce_outbox_event
WHERE (aggregate_type='commerce_order'
       AND aggregate_id IN (SELECT CAST(id AS CHAR) FROM commerce_order WHERE voucher_id={VOUCHER_ID}))
   OR (aggregate_type='commerce_seckill_voucher' AND aggregate_id=CAST({VOUCHER_ID} AS CHAR));
DELETE FROM commerce_order_recovery WHERE voucher_id={VOUCHER_ID};
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
    prefix = voucher_prefix(generation, shard_count, VOUCHER_ID)
    stock_key = f"{prefix}seckill:stock:{VOUCHER_ID}"
    buyers_key = f"{prefix}seckill:buyers:{VOUCHER_ID}"
    inflight_key = f"{prefix}seckill:inflight:{VOUCHER_ID}"
    metadata_key = f"{prefix}seckill:meta:{VOUCHER_ID}"
    revision_key = f"{prefix}seckill:meta-revision:{VOUCHER_ID}"
    update_fence_key = f"{prefix}seckill:meta-update-fence:{VOUCHER_ID}"
    voucher_idempotency_removed = delete_matching_keys(
        f"commerce:{{commerce:*}}:seckill:idempotency:{VOUCHER_ID}:*",
        lambda key: belongs_to_voucher_load_user(key, users),
    )
    global_idempotency_removed = delete_matching_keys(
        "commerce:{commerce-idempotency:*}:*",
        lambda key: belongs_to_load_user(key, users),
    )
    now_millis = int(time.time() * 1000)
    commands: list[tuple[object, ...]] = [
        *(("DEL", f"login:token:{token}") for token in previous_tokens),
        ("DEL", stock_key),
        ("DEL", buyers_key),
        ("DEL", inflight_key),
        ("DEL", metadata_key),
        ("DEL", revision_key),
        ("DEL", update_fence_key),
        ("SET", stock_key, stock),
        (
            "HSET", metadata_key,
            "voucherId", VOUCHER_ID,
            "type", 1,
            "status", 1,
            "beginEpochMillis", now_millis - 86_400_000,
            "endEpochMillis", now_millis + 86_400_000,
            "sourceVersion", "load-test",
        ),
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
        "routingGeneration": generation,
        "physicalShardCount": shard_count,
        "voucherShard": physical_shard(VOUCHER_ID, shard_count),
        "voucherIdempotencyRemoved": voucher_idempotency_removed,
        "globalIdempotencyRemoved": global_idempotency_removed,
        "previousTokensRemoved": len(previous_tokens),
        "tokens": str(TOKENS),
    }, indent=2))


def verify(
        initial_stock: int,
        users: int,
        generation: str,
        shard_count: int,
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
SELECT COUNT(*) FROM commerce_order_recovery
WHERE voucher_id={VOUCHER_ID}
AND phase IN ('RECEIVED','COMPENSATION_REQUIRED','RESTORE_PENDING');
SELECT COUNT(*),
       COALESCE(SUM(status='PREPARED'),0),
       COALESCE(SUM(status='ACCEPTED'),0),
       COALESCE(SUM(status='REPAIR_REQUIRED'),0),
       COALESCE(SUM(status='ORDER_CREATED'),0)
FROM commerce_admission_ledger
WHERE voucher_id={VOUCHER_ID}
AND routing_generation='{generation}' AND physical_shard_count={shard_count}
AND user_id>={START_USER_ID} AND user_id<{START_USER_ID + users};
SELECT COUNT(*) FROM commerce_admission_ledger
WHERE voucher_id={VOUCHER_ID}
AND user_id>={START_USER_ID} AND user_id<{START_USER_ID + users}
AND NOT (
  routing_generation='{generation}' AND physical_shard_count={shard_count}
);
""")
    order_count, distinct_users, minimum_user, maximum_user = map(int, rows[0].split("\t"))
    database_stock = int(rows[1])
    unpublished = int(rows[2])
    dead_letters = int(rows[3])
    reservation_count, order_created_reservations = map(int, rows[4].split("\t"))
    unresolved_recovery = int(rows[5])
    (
        ledger_count,
        ledger_prepared,
        ledger_accepted,
        ledger_repair_required,
        ledger_order_created,
    ) = map(int, rows[6].split("\t"))
    cross_generation_ledger = int(rows[7])
    env = env_file()
    prefix = voucher_prefix(generation, shard_count, VOUCHER_ID)
    # Read only the Commerce keys with the administrative test harness account.
    with socket.create_connection(("127.0.0.1", int(env.get("CITYCOPILOT_REDIS_PORT", "6380"))), timeout=10) as connection:
        connection.sendall(
            encode("AUTH", env["CITYCOPILOT_REDIS_PASSWORD"])
            + encode("GET", f"{prefix}seckill:stock:{VOUCHER_ID}")
            + encode("SCARD", f"{prefix}seckill:buyers:{VOUCHER_ID}")
            + encode("SCARD", f"{prefix}seckill:inflight:{VOUCHER_ID}")
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
        inflight_reply = reader.readline()
        if not inflight_reply.startswith(b":"):
            raise RuntimeError("Unexpected Redis in-flight SCARD response")
        redis_inflight = int(inflight_reply[1:])
    stream = stream_status(generation, shard_count)
    current_global_prefix = (
        f"commerce:{{commerce-idempotency:{generation}:{shard_count}:"
    )
    cross_generation_global_keys = [
        key for key in redis_cli("--scan", "--pattern", "commerce:{commerce-idempotency:*}:*")
        if key
        and belongs_to_load_user(key, users)
        and not key.startswith(current_global_prefix)
    ]
    report = {
        "voucherId": VOUCHER_ID,
        "routingGeneration": generation,
        "physicalShardCount": shard_count,
        "voucherShard": physical_shard(VOUCHER_ID, shard_count),
        "orders": order_count,
        "distinctUsers": distinct_users,
        "userRange": [minimum_user, maximum_user],
        "reservations": reservation_count,
        "orderCreatedReservations": order_created_reservations,
        "databaseStock": database_stock,
        "redisStock": redis_stock,
        "redisBuyerCount": redis_buyers,
        "redisInFlight": redis_inflight,
        "unresolvedRecovery": unresolved_recovery,
        "admissionLedgerCount": ledger_count,
        "admissionLedgerPrepared": ledger_prepared,
        "admissionLedgerAccepted": ledger_accepted,
        "admissionLedgerRepairRequired": ledger_repair_required,
        "admissionLedgerOrderCreated": ledger_order_created,
        "crossGenerationAdmissionLedger": cross_generation_ledger,
        "crossGenerationGlobalIdempotencyKeys": len(cross_generation_global_keys),
        "streamPending": stream["pending"],
        "streamLag": stream["lag"],
        "streamDrained": stream["drained"],
        "streamShards": stream["shards"],
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
        and redis_inflight == 0
        and unresolved_recovery == 0
        and ledger_prepared == 0
        and ledger_accepted == 0
        and ledger_repair_required == 0
        and ledger_order_created == order_count
        and cross_generation_ledger == 0
        and not cross_generation_global_keys
        and report["streamDrained"]
        and dead_letters == 0
        and outbox_valid
    )
    return 0 if valid else 2


def main() -> int:
    parser = argparse.ArgumentParser()
    defaults = env_file()
    parser.add_argument("--stock", type=int, default=300)
    parser.add_argument("--users", type=int, default=300)
    parser.add_argument("--drain-timeout", type=int, default=600)
    parser.add_argument(
        "--routing-generation",
        default=defaults.get("CITYCOPILOT_COMMERCE_REDIS_ROUTING_GENERATION", "v1"),
    )
    parser.add_argument(
        "--physical-shard-count",
        type=int,
        default=int(defaults.get("CITYCOPILOT_COMMERCE_REDIS_PHYSICAL_SHARD_COUNT", "1")),
    )
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
    if not SAFE_GENERATION.fullmatch(args.routing_generation):
        parser.error("--routing-generation must match ^[A-Za-z0-9_-]{1,24}$")
    if not 1 <= args.physical_shard_count <= 1024:
        parser.error("--physical-shard-count must be between 1 and 1024")
    if args.expected_orders is not None and not 0 <= args.expected_orders <= args.stock:
        parser.error("--expected-orders must be between zero and --stock")
    if args.verify:
        if args.wait_for_drain:
            wait_for_stream_drain(
                args.drain_timeout,
                args.routing_generation,
                args.physical_shard_count,
            )
        return verify(
            args.stock,
            args.users,
            args.routing_generation,
            args.physical_shard_count,
            args.allow_unpublished,
            args.expected_orders,
        )
    seed(
        args.stock,
        args.users,
        args.drain_timeout,
        args.routing_generation,
        args.physical_shard_count,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
