#!/usr/bin/env python3
"""HTTP load and fault checks against the isolated redesign Compose project only.

Scenarios: seckill through Core, 80% hot shop/detail and voucher reads, concurrent
booking; or, with --sold-out-storm, only a small-stock seckill hit by every user; two Commerce timeout workers; kill a PEL owner; kill the only active
Outbox publisher during a blocked send; Redis outage followed by evidence repair.
Fixtures are additive and tagged by run id. No business database or volume is used.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

from run_booking_baseline import Runner as BookingRunner, latency
from run_multiinstance_acceptance import COMPOSE, ROOT, command, inventory, sql, wait_for

CORE = "http://127.0.0.1:18092"
COMMERCE = "http://127.0.0.1:18094/commerce/v1"


def request(url, method="GET", body=None, headers=None):
    started = time.perf_counter()
    req = urllib.request.Request(url, method=method, headers={"Content-Type": "application/json", **(headers or {})},
        data=None if method == "GET" else json.dumps(body or {}).encode())
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            status, raw, retry = response.status, response.read(), response.headers.get("Retry-After")
    except urllib.error.HTTPError as error:
        status, raw, retry = error.code, error.read(), error.headers.get("Retry-After")
    except (OSError, TimeoutError) as error:
        return {"status": 0, "body": {"code": type(error).__name__}, "latency_ms": (time.perf_counter() - started) * 1000}
    try:
        payload = json.loads(raw) if raw else {}
    except ValueError:
        payload = {"code": f"HTTP_{status}"}
    return {"status": status, "body": payload, "retryAfter": retry, "latency_ms": (time.perf_counter() - started) * 1000}


def required(result):
    assert 200 <= result["status"] < 300 and result["body"].get("success", True), result
    return result["body"]


def redis(*args):
    raw = command(COMPOSE + ["exec", "-T", "redis", "sh", "-ec",
        'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --json "$@"', "redis-cli", *map(str, args)])
    return json.loads(raw)


def redis_pipeline(commands):
    payload = bytearray()
    for parts in commands:
        payload.extend(f"*{len(parts)}\r\n".encode())
        for part in parts:
            value = str(part).encode()
            payload.extend(f"${len(value)}\r\n".encode() + value + b"\r\n")
    result = command(COMPOSE + ["exec", "-T", "redis", "sh", "-ec",
        'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --pipe'], bytes(payload))
    assert re.search(r"errors:\s*0,", result), "Redis pipeline reported command errors"


def target_traffic(key, password, work):
    """Count fixture-key Redis commands, including Lua subcommands; never retain MONITOR lines."""
    counts = {name: 0 for name in ("eval", "evalsha", "get")}
    barrier_key = "load-monitor:" + uuid.uuid4().hex
    observed = threading.Event()
    target = '"' + key + '"'
    with socket.create_connection(("127.0.0.1", 16389), timeout=10) as connection:
        reader = connection.makefile("rb")
        secret = password.encode()
        connection.sendall(b"*2\r\n$4\r\nAUTH\r\n$" + str(len(secret)).encode() + b"\r\n" + secret + b"\r\n")
        assert reader.readline() == b"+OK\r\n", "Redis measurement authentication failed"
        connection.sendall(b"*1\r\n$7\r\nMONITOR\r\n")
        assert reader.readline() == b"+OK\r\n", "Redis MONITOR was not accepted"
        connection.settimeout(None)

        def consume():
            for raw in reader:
                line = raw.decode("utf-8", errors="replace")
                if '"' + barrier_key + '"' in line:
                    observed.set()
                if target in line:
                    matched = re.search(r'\] "([^\"]+)"', line)
                    if matched and matched[1].lower() in counts:
                        counts[matched[1].lower()] += 1

        consumer = threading.Thread(target=consume, daemon=True)
        consumer.start()
        started = time.perf_counter()
        try:
            value = work()
            duration = time.perf_counter() - started
            # The marker is a different key. It drains the monitor stream without adding a target GET.
            redis("GET", barrier_key)
            assert observed.wait(10), "Redis command measurement did not reach its completion marker"
            return value, {"targetKey": key, "durationSeconds": round(duration, 3), "commands": dict(counts),
                "counting": "Only commands naming the fixture key; Lua subcommands included. PUBLISH, AUTH, MONITOR and barrier GET excluded."}
        finally:
            connection.shutdown(socket.SHUT_RDWR)
            consumer.join(timeout=5)
            reader.close()


def seed(users, run_id):
    base = time.time_ns() // 1000
    identities = list(range(base, base + users))
    merchant, shop = base + users, base + users + 1
    rows = [f"({user},'19{user % 1000000000:09d}','{run_id}','user')" for user in identities]
    rows.append(f"({merchant},'19{merchant % 1000000000:09d}','{run_id}','merchant')")
    sql("mysql", "INSERT INTO user_account(id,phone,nick_name,role) VALUES " + ",".join(rows) + ";")
    sql("mysql", f"INSERT INTO shop_type(id,name) VALUES ({shop},'{run_id}');" +
        "INSERT INTO shop(id,name,type_id,owner_user_id,images,address,sold,comments,score) VALUES " +
        ",".join(f"({shop+i},'{run_id}-{i}',{shop},{merchant},'fixture.png','load-test',0,0,50)" for i in range(10)) + ";")
    tokens = [uuid.uuid4().hex for _ in identities]
    commands = []
    for user, token in zip(identities, tokens):
        for parts in (("HSET", "login:token:" + token, "id", user, "nickName", run_id, "role", "user"),
                      ("EXPIRE", "login:token:" + token, 3600)):
            commands.append(parts)
    redis_pipeline(commands)
    return identities, tokens, merchant, shop


class IsolatedBookingRunner(BookingRunner):
    def sql(self, statement):
        return sql("commerce-mysql", statement)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--users", type=int, default=300)
    parser.add_argument("--concurrency", type=int, default=50)
    parser.add_argument("--read-requests", type=int, default=3000)
    parser.add_argument("--faults", action="store_true")
    parser.add_argument("--faults-only", action="store_true",
        help="Run Stream/Outbox/Redis faults with fresh fixtures; skip baseline load, cache, booking and timeout checks")
    parser.add_argument("--sold-out-storm", action="store_true",
        help="Only run one --storm-stock voucher against all --users and record Commerce MySQL counters")
    parser.add_argument("--storm-stock", type=int, default=100)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    assert 2 <= args.users <= 10000 and 1 <= args.concurrency <= 1000 and args.read_requests >= 100
    services = inventory()
    # Read only the token needed by this caller; never put container environments into evidence.
    environment = dict(value.split("=", 1) for value in services["commerce"][0]["Config"]["Env"] if "=" in value)
    token = environment["CITYCOPILOT_INTERNAL_COMMERCE_TOKEN"]
    redis_password = next(value.split("=", 1)[1] for value in services["redis"][0]["Config"]["Env"]
                          if value.startswith("REDIS_PASSWORD="))
    os.environ["CITYCOPILOT_INTERNAL_COMMERCE_TOKEN"] = token
    run_id = "redesign-" + uuid.uuid4().hex[:12]
    report = {"runId": run_id, "scenarios": {}, "passed": False,
        "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
        "mode": "sold-out-storm" if args.sold_out_storm else "faults-only" if args.faults_only
                else "load-and-faults" if args.faults else "load",
        "workload": {"users": args.users, "concurrency": args.concurrency,
            "readRequests": 0 if args.faults_only else args.read_requests},
        "images": {name: sorted({container["Image"] for container in containers}) for name, containers in services.items()},
        "brokerBudget": {"containerBytes": services["rocketmq-broker"][0]["HostConfig"]["Memory"],
            "jvmOptions": next(value.split("=", 1)[1] for value in services["rocketmq-broker"][0]["Config"]["Env"]
                               if value.startswith("JAVA_OPT_EXT="))},
        "dockerResources": json.loads(command(["docker", "info", "--format", '{"cpus":{{.NCPU}},"memoryBytes":{{.MemTotal}}}'])),
        "scope": "Isolated local 2 Core + 2 Commerce; " + (
            "Stream/Outbox/Redis fault checks only, with fresh fixtures; excludes baseline load, cache comparisons, booking, IP limit and two-instance timeout checks"
            if args.faults_only else "backend load uses loopback upstream listeners; IP limit checked separately")}
    samples, cpu = [], []
    stage = "fixture"
    stopped, paused = set(), set()
    redis_needs_acl = False
    done = threading.Event()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    container_ids = [c["Id"] for group in services.values() for c in group]

    def sample_cpu():
        while not done.is_set():
            sampled_stage = stage
            result = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}", *container_ids],
                                    capture_output=True, text=True, timeout=30)
            if result.returncode:
                cpu.append({"stage": sampled_stage, "error": "docker stats failed"})
            else:
                for line in result.stdout.splitlines():
                    value = json.loads(line)
                    cpu.append({"stage": sampled_stage, "at": datetime.now(timezone.utc).isoformat(),
                                "container": value["Name"], "cpuPercent": value["CPUPerc"], "memory": value["MemUsage"]})
            done.wait(.25)

    def commerce(path, method="GET", body=None, actor=None, key=None):
        headers = {"X-CityCopilot-Service-Token": token, "X-CityCopilot-User-Id": str(actor or merchant)}
        if key:
            headers["Idempotency-Key"] = key
        return request(COMMERCE + path, method, body, headers)

    def voucher(label, stock):
        now = datetime.now(timezone.utc)
        result = required(commerce("/vouchers", "POST", {"shopId": shop, "title": run_id + "-" + label,
            "payValue": 100, "actualValue": 200, "type": 1, "status": 1, "stock": stock,
            "beginTime": (now - timedelta(hours=1)).isoformat(), "endTime": (now + timedelta(days=1)).isoformat()}))
        return int(result["id"])

    def load(label, count, operation):
        started = time.perf_counter()
        with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            results = list(pool.map(operation, range(count)))
        duration = time.perf_counter() - started
        samples.extend({"scenario": label, "status": value["status"], "latency_ms": value["latency_ms"],
                        "code": value["body"].get("code")} for value in results)
        summary = {"requests": count, "durationSeconds": round(duration, 3),
                   "qps": round(count / duration, 2), "latencyMs": latency([value["latency_ms"] for value in results]),
                   "accepted": sum(200 <= value["status"] < 300 and value["body"].get("success", True) for value in results),
                   "httpStatuses": {str(code): sum(value["status"] == code for value in results)
                                    for code in sorted({value["status"] for value in results})}}
        report["scenarios"][label] = summary
        return results, summary

    def order_count(voucher_id):
        return int(sql("commerce-mysql", f"SELECT COUNT(*) FROM commerce_order WHERE voucher_id={voucher_id};"))

    def verify_orders(voucher_id, expected, stock):
        wait_for(lambda: order_count(voucher_id) == expected, 180)
        fact = json.loads(sql("commerce-mysql", f"""SELECT JSON_OBJECT(
            'orders',COUNT(*),'users',COUNT(DISTINCT user_id),'active',COALESCE(SUM(status<>4),0),
            'ledger',(SELECT COUNT(*) FROM commerce_admission_ledger WHERE voucher_id={voucher_id} AND status='ORDER_CREATED'),
            'dbStock',(SELECT stock FROM commerce_seckill_voucher WHERE voucher_id={voucher_id}))
            FROM commerce_order WHERE voucher_id={voucher_id};"""))
        assert fact["orders"] == fact["users"] == fact["ledger"] == expected, fact
        assert fact["dbStock"] + fact["active"] == stock, fact
        evidence = wait_for(lambda: data if (data := required(commerce(f"/consistency/{voucher_id}")))["consistent"] else None, 60)
        fact["redisStock"] = evidence["redisStock"]
        assert fact["redisStock"] == fact["dbStock"], fact
        return fact

    def stop(container_id):
        stopped.add(container_id)
        command(["docker", "kill", container_id])

    def duplicate_deliveries():
        count = 0
        for container in services["core"]:
            metrics = command(["docker", "exec", container["Id"], "curl", "--fail", "--silent",
                               "http://127.0.0.1:8081/actuator/prometheus"])
            count += sum(float(line.rsplit(None, 1)[1]) for line in metrics.splitlines()
                         if line.startswith("citycopilot_rocketmq_consume_total{") and 'outcome="duplicate"' in line)
        return count

    def start(container_id):
        command(["docker", "start", container_id])
        stopped.discard(container_id)
        wait_for(lambda: json.loads(command(["docker", "inspect", "--format", "{{json .State}}", container_id]))
                 .get("Health", {}).get("Status") == "healthy", 150)

    def restore_redis():
        nonlocal redis_needs_acl
        start(services["redis"][0]["Id"])
        # ACL SETUSER is not persisted in this deployment's AOF; restore it after every restart.
        command(COMPOSE + ["run", "--rm", "--no-deps", "redis-init"])
        for container in services["commerce"]:
            wait_for(lambda: json.loads(command(["docker", "exec", container["Id"], "curl", "--silent",
                "--max-time", "10", "http://127.0.0.1:8083/actuator/health"])).get("status") == "UP", 90)
        redis_needs_acl = False

    sampler = threading.Thread(target=sample_cpu, daemon=True)
    sampler.start()
    try:
        users, tokens, merchant, shop = seed(args.users, run_id)
        if args.sold_out_storm:
            stage = "sold-out-storm"
            storm = voucher(stage, args.storm_stock)
            counters = ("Questions", "Com_select", "Com_insert", "Com_update", "Innodb_rows_inserted")

            def mysql_status():
                rows = sql("commerce-mysql", "SHOW GLOBAL STATUS WHERE Variable_name IN ("
                           + ",".join(f"'{name}'" for name in counters) + ");")
                return {name: int(value) for name, value in (line.split("	") for line in rows.splitlines())}

            def ledger_rows():
                return int(sql("commerce-mysql", f"SELECT COUNT(*) FROM commerce_admission_ledger WHERE voucher_id={storm};"))

            before, ledger_before = mysql_status(), ledger_rows()
            results, summary = load(stage, args.users, lambda i: request(CORE + f"/voucher-order/seckill/{storm}", "POST",
                headers={"Authorization": tokens[i], "Idempotency-Key": f"{run_id}:storm:{i}"}))
            after, ledger_after = mysql_status(), ledger_rows()
            # Background relay, expiry and reconciliation polling also query MySQL; measure them over the same length.
            time.sleep(summary["durationSeconds"])
            idle = mysql_status()
            summary["codes"] = {code: sum(str(value["body"].get("code")) == code for value in results)
                                for code in sorted({str(value["body"].get("code")) for value in results})}
            summary["stock"] = args.storm_stock
            summary["ledgerRowsAdded"] = ledger_after - ledger_before
            summary["commerceMysqlDuringStorm"] = {name: after[name] - before[name] for name in counters}
            summary["commerceMysqlIdleSameDuration"] = {name: idle[name] - after[name] for name in counters}
            assert summary["accepted"] == args.storm_stock, summary
            summary["invariants"] = verify_orders(storm, args.storm_stock, args.storm_stock)
        elif not args.faults_only:
            stage = "seckill"
            baseline = voucher("seckill", args.users)
            _, summary = load(stage, args.users, lambda i: request(CORE + f"/voucher-order/seckill/{baseline}", "POST",
                headers={"Authorization": tokens[i], "Idempotency-Key": f"{run_id}:baseline:{i}"}))
            assert summary["accepted"] == args.users, summary
            summary["invariants"] = verify_orders(baseline, args.users, args.users)

            stage = "shop-hotspot"
            _, summary = load(stage, args.read_requests, lambda i: request(CORE +
                (f"/shop/{shop if i % 5 else shop + 1 + i % 9}" if i % 2 else
                 f"/voucher/list/{shop if i % 5 else shop + 1 + i % 9}")))
            assert summary["accepted"] == args.read_requests, summary

            stage = "shop-l1-redis-comparison"
            current = inventory()
            assert {c["Id"] for c in current["core"] + current["commerce"]} == {c["Id"] for c in services["core"] + services["commerce"]}
            for _ in range(4):
                required(request(CORE + f"/shop/{shop}"))
            shop_key = f"cache:shop:{shop}"
            assert redis("GET", shop_key), "The Redis comparison requires a populated shop cache"
            (_, http_read), local_reads = target_traffic(shop_key, redis_password,
                lambda: load(stage + "-http", args.read_requests, lambda _: request(CORE + f"/shop/{shop}")))
            assert http_read["accepted"] == args.read_requests, http_read
            _, direct_reads = target_traffic(shop_key, redis_password,
                lambda: redis_pipeline([("GET", shop_key)] * args.read_requests))
            assert direct_reads["commands"]["get"] == args.read_requests, direct_reads
            local_gets = local_reads["commands"]["get"]
            report["scenarios"][stage] = {"requestsPerArm": args.read_requests, "runningCore": 2, "runningCommerce": 2,
                "localCacheHttp": local_reads, "directRedisReference": direct_reads,
                "redisGetsPerHttpRequest": local_gets / args.read_requests,
                "observedRedisGetQps": local_gets / local_reads["durationSeconds"],
                "referenceRedisGetQpsAtSameHttpRate": args.read_requests / local_reads["durationSeconds"],
                "redisGetReductionPercent": round(100 * (1 - local_gets / args.read_requests), 2),
                "scope": "Warm single-shop HTTP burst versus equal-count Redis GET reference. QPS comparison uses the same measured HTTP duration; pipelined GET throughput is not HTTP or production capacity. Expiry and same-key background reads remain in the observed count."}
            assert local_gets < args.read_requests, report["scenarios"][stage]

            stage = "sold-out-redis-comparison"
            comparison_count = min(64, max(16, args.users))
            empty_voucher = voucher(stage, 1)
            required(commerce(f"/seckill/{empty_voucher}", "POST", actor=users[0], key=run_id + ":sold-out:first"))
            verify_orders(empty_voucher, 1, 1)
            stock_keys = redis("KEYS", f"commerce:*:seckill:stock:{empty_voucher}")
            assert len(stock_keys) == 1
            stock_key = stock_keys[0]

            def sold_out(i, arm):
                result = commerce(f"/seckill/{empty_voucher}", "POST", actor=users[1], key=f"{run_id}:sold-out:{arm}:{i}")
                assert result["status"] == 409 and result["body"].get("code") == "SECKILL_STOCK_NOT_ENOUGH", result
                return result

            # Warm both replicas through the same round-robin listener before measuring normal behavior.
            for i in range(4):
                sold_out(i, "warm")
            (_, fast_http), fast_counts = target_traffic(stock_key, redis_password,
                lambda: load(stage + "-normal", comparison_count, lambda i: sold_out(i, "normal")))

            def uncached_sold_out():
                latencies = []
                for i in range(comparison_count):
                    subscribers = redis("PUBLISH", "commerce:inventory:restored", stock_key)
                    assert subscribers == 2, "Both Commerce invalidation listeners must be active"
                    latencies.append(sold_out(i, "invalidated")["latency_ms"])
                return {"requests": comparison_count, "httpLatencyMs": latency(latencies),
                        "invalidationPublishes": comparison_count}

            slow_http, slow_counts = target_traffic(stock_key, redis_password, uncached_sold_out)
            fast_lua = fast_counts["commands"]["eval"] + fast_counts["commands"]["evalsha"]
            slow_lua = slow_counts["commands"]["eval"] + slow_counts["commands"]["evalsha"]
            # Do not claim an uncached reference unless the actual measured requests reached the Lua path.
            assert slow_lua >= comparison_count, slow_counts
            report["scenarios"][stage] = {"requestsPerArm": comparison_count, "runningCore": 2, "runningCommerce": 2,
                "normal": {"http": fast_http, "redis": fast_counts},
                "invalidateBeforeEachRequest": {"http": slow_http, "redis": slow_counts},
                "luaCallsPerRequest": {"normal": fast_lua / comparison_count, "invalidated": slow_lua / comparison_count},
                "luaCallReductionPercent": round(100 * (1 - fast_lua / slow_lua), 2),
                "scope": "Normal concurrent sold-out burst versus serial requests with broadcast invalidation. Control publishes are excluded; this compares commands per request, not throughput. The one-second marker TTL remains active; same-key background GETs are reported unchanged."}
            assert fast_lua < slow_lua, report["scenarios"][stage]

            stage = "booking"
            booking = IsolatedBookingRunner(SimpleNamespace(base_url=COMMERCE, service_token_env="CITYCOPILOT_INTERNAL_COMMERCE_TOKEN",
                concurrency=args.concurrency, timeout=20, shop_id=shop, merchant_id=merchant))
            rules = booking.required(booking.call("GET", "/booking-rules", merchant))
            start_at = (datetime.fromisoformat(rules["serverTime"].replace("Z", "+00:00")) + timedelta(days=1, hours=1))
            start_at = start_at.astimezone(timezone.utc).replace(minute=0, second=0, microsecond=0)
            summary, booking_samples = booking.run_scenario("hot-court", users, start_at)
            report["scenarios"][stage] = summary
            samples.extend(booking_samples)
            assert summary["unexpected"] == 0, summary
            invariants = booking.invariants()
            assert all(invariants[key] == 0 for key in ("capacityViolations", "occupancyMismatches", "unitMismatches", "missingVersionEvents", "held")), invariants
            summary["invariants"] = invariants

            stage = "ip-limit"
            results, summary = load(stage, 200, lambda _: request(f"http://127.0.0.1:18090/api/voucher-order/seckill/{baseline}", "POST"))
            limited = [result for result in results if result["status"] == 429]
            assert limited and all(result["retryAfter"] == "1" for result in limited), summary

            stage = "two-instance-close"
            sql("commerce-mysql", f"UPDATE commerce_order SET pay_deadline=CURRENT_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE voucher_id={baseline} AND status=1;")
            wait_for(lambda: int(sql("commerce-mysql", f"SELECT COUNT(*) FROM commerce_order WHERE voucher_id={baseline} AND status=4 AND close_reason='PAY_TIMEOUT';")) == args.users, 180)
            closed = verify_orders(baseline, args.users, args.users)
            recovery = json.loads(sql("commerce-mysql", f"""SELECT JSON_OBJECT('rows',COUNT(*),'restored',SUM(phase='RESTORED'))
                FROM commerce_order_recovery WHERE voucher_id={baseline} AND source_type='STATE_TRANSITION';"""))
            assert closed["dbStock"] == args.users and recovery["rows"] == recovery["restored"] == args.users, (closed, recovery)
            report["scenarios"][stage] = {**closed, "recovery": recovery, "runningCommerce": len(services["commerce"])}

        if args.faults or args.faults_only:
            stage = "stream-kill"
            stream_voucher = voucher(stage, args.users)
            with ThreadPoolExecutor(max_workers=1) as background:
                running = background.submit(load, stage, args.users, lambda i: commerce(f"/seckill/{stream_voucher}", "POST",
                    actor=users[i], key=f"{run_id}:stream:{i}"))
                def pending_owner():
                    keys = redis("KEYS", "commerce:*:stream:orders")
                    for stream in keys:
                        entries = redis("XPENDING", stream, "commerce-order-workers", "-", "+", 100)
                        for entry in entries:
                            for container in services["commerce"]:
                                if entry[1].startswith(container["Config"]["Hostname"] + "-"):
                                    return {"container": container, "stream": stream, "entry": entry}
                    return None
                pending = wait_for(pending_owner, 30)
                stop(pending["container"]["Id"])
                results, summary = running.result()
                # Requests interrupted before the client sees 202 retain their original identity on replay.
                for i, result in enumerate(results):
                    if not 200 <= result["status"] < 300:
                        required(commerce(f"/seckill/{stream_voucher}", "POST", actor=users[i], key=f"{run_id}:stream:{i}"))
                summary["interruptedRequestsReplayed"] = args.users - summary["accepted"]
                summary["killedPendingOwner"] = pending["entry"][1]
                summary["invariants"] = verify_orders(stream_voucher, args.users, args.users)
                assert not redis("XPENDING", pending["stream"], "commerce-order-workers", "-", "+", 100)
                start(pending["container"]["Id"])

            stage = "outbox-kill"
            # The main scheduler also consumes Stream records; blocked older sends would delay this fixture.
            wait_for(lambda: int(sql("commerce-mysql",
                "SELECT COUNT(*) FROM commerce_outbox_event WHERE status<>'PUBLISHED';")) == 0, 180)
            outbox_voucher = voucher(stage, 1)
            first, second = services["commerce"]
            stop(second["Id"])
            proxy_id = command(COMPOSE + ["ps", "-q", "rocketmq-proxy"])
            paused.add(proxy_id)
            command(["docker", "pause", proxy_id])
            outbox_summary = {"acceptanceAttempts": []}
            report["scenarios"][stage] = outbox_summary
            def accept_outbox_order():
                result = commerce(f"/seckill/{outbox_voucher}", "POST", actor=users[0], key=run_id + ":outbox")
                attempt = {"status": result["status"], "code": result["body"].get("code"),
                           "latency_ms": result["latency_ms"]}
                outbox_summary["acceptanceAttempts"].append(attempt)
                samples.append({"scenario": stage + "-acceptance", **attempt})
                # nginx can retain the stopped peer until DNS refresh; unknown outcomes replay the same identity.
                if result["status"] in (0, 502, 503, 504):
                    return None
                return required(result)
            wait_for(accept_outbox_order, 30)
            def claimed_event():
                return sql("commerce-mysql", f"""SELECT e.event_id FROM commerce_outbox_event e
                    JOIN commerce_order o ON e.aggregate_id=CAST(o.id AS CHAR)
                    WHERE o.voucher_id={outbox_voucher} AND e.aggregate_type='commerce_order'
                      AND e.status='CLAIMED' LIMIT 1;""")
            event_id = wait_for(claimed_event, 60)
            stop(first["Id"])
            command(["docker", "unpause", proxy_id])
            paused.remove(proxy_id)
            start(second["Id"])
            wait_for(lambda: sql("commerce-mysql", f"SELECT status FROM commerce_outbox_event WHERE event_id='{event_id}';") == "PUBLISHED", 180)
            def inbox():
                return sql("mysql", f"SELECT CONCAT(status,':',processed_at) FROM processed_event WHERE event_id='{event_id}';")
            recorded = wait_for(inbox, 180)
            assert recorded.startswith("PROCESSED:"), recorded
            duplicates_before = duplicate_deliveries()
            # Deliberately resend the identical persisted event to exercise the receiving Inbox.
            sql("commerce-mysql", f"UPDATE commerce_outbox_event SET status='READY',next_attempt_at=CURRENT_TIMESTAMP(3) WHERE event_id='{event_id}';")
            wait_for(lambda: sql("commerce-mysql", f"SELECT status FROM commerce_outbox_event WHERE event_id='{event_id}';") == "PUBLISHED", 90)
            duplicates_after = wait_for(lambda: count if (count := duplicate_deliveries()) > duplicates_before else None, 180)
            assert inbox() == recorded, "Duplicate publication changed the Inbox side effect"
            outbox_summary.update({"eventId": event_id, "inboxUnchangedOnReplay": True,
                "duplicateDeliveriesObserved": duplicates_after - duplicates_before,
                "invariants": verify_orders(outbox_voucher, 1, 1)})
            start(first["Id"])

            stage = "redis-restart"
            redis_voucher = voucher(stage, 2)
            redis_id = services["redis"][0]["Id"]
            stopped.add(redis_id)
            redis_needs_acl = True
            command(["docker", "stop", "--time", "1", redis_id])
            failed = commerce(f"/seckill/{redis_voucher}", "POST", actor=users[0], key=run_id + ":redis-down")
            assert failed["status"] in (0, 500, 502, 503, 504), failed
            assert int(sql("commerce-mysql", f"SELECT COUNT(*) FROM commerce_admission_ledger WHERE voucher_id={redis_voucher} AND status IN ('ACCEPTED','ORDER_CREATED');")) == 0
            restore_redis()
            # AOF restart normally preserves stock; lose this fixture's stock key to exercise repair too.
            stock_keys = redis("KEYS", f"commerce:*:seckill:stock:{redis_voucher}")
            assert len(stock_keys) == 1, stock_keys
            redis("DEL", stock_keys[0])
            evidence = required(commerce(f"/consistency/{redis_voucher}"))
            assert not evidence["consistent"]
            required(commerce(f"/consistency/{redis_voucher}/repair", "POST", {"expectedEvidenceHash": evidence["evidenceHash"]}))
            required(commerce(f"/seckill/{redis_voucher}", "POST", actor=users[1], key=run_id + ":redis-up"))
            report["scenarios"][stage] = {"outageHttpStatus": failed["status"], "fixtureStockLossInjected": True,
                "invariants": verify_orders(redis_voucher, 1, 2)}
        report["passed"] = True
    except Exception as error:
        report["failure"] = {"stage": stage, "message": str(error)}
        raise
    finally:
        restoration_failures = []
        for container_id in paused:
            try:
                command(["docker", "unpause", container_id])
            except RuntimeError as error:
                restoration_failures.append(str(error))
        for container_id in stopped:
            try:
                command(["docker", "start", container_id])
            except RuntimeError as error:
                restoration_failures.append(str(error))
        if redis_needs_acl:
            try:
                restore_redis()
            except (RuntimeError, AssertionError) as error:
                restoration_failures.append(str(error))
        done.set()
        sampler.join(timeout=35)
        if restoration_failures:
            report["passed"] = False
            report["restorationFailures"] = restoration_failures
        report["cpuSamples"] = len(cpu)
        for name in {value.get("container") for value in cpu if "container" in value}:
            values = [float(value["cpuPercent"].rstrip("%")) for value in cpu
                      if value.get("container") == name and value["cpuPercent"].endswith("%")]
            if values:
                report.setdefault("cpuByContainer", {})[name] = {"meanPercent": round(statistics.fmean(values), 2), "maxPercent": max(values)}
        args.output.with_suffix(".samples.jsonl").write_text("".join(json.dumps(value) + "\n" for value in samples), encoding="utf-8")
        args.output.with_suffix(".cpu.jsonl").write_text("".join(json.dumps(value) + "\n" for value in cpu), encoding="utf-8")
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))
        if restoration_failures:
            raise RuntimeError("The isolated deployment needs manual container restoration; see the report")


if __name__ == "__main__":
    main()
