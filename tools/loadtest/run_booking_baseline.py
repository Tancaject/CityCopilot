#!/usr/bin/env python3
"""Run booking HTTP load against an explicitly isolated local *_load_test database.

Requires an already running Commerce instance, real Core merchant/shop/user fixtures,
and the existing MySQL CLI. Uses Python's standard library only. Never deletes data.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import platform
import statistics
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from pathlib import Path

from run_seckill_baseline import percentile


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18093/commerce/v1")
    parser.add_argument("--service-token-env", default="CITYCOPILOT_INTERNAL_COMMERCE_TOKEN")
    parser.add_argument("--users-file", type=Path, required=True, help="CSV with one existing Core user ID per row; optional userId header")
    parser.add_argument("--shop-id", type=int, required=True)
    parser.add_argument("--merchant-id", type=int, required=True)
    parser.add_argument("--users", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=20)
    parser.add_argument("--timeout", type=float, default=15)
    parser.add_argument("--scenario", choices=("all", "hot-court", "dining", "nonhot"), default="all")
    parser.add_argument("--mq-outage", action="store_true", help="Stop only the isolated booking-load broker/proxy during load, then restore and verify Outbox recovery")
    parser.add_argument("--require-outbox-drain", action="store_true", help="Also require all run events to be published after a normal load")
    parser.add_argument("--docker-exe", default="docker")
    parser.add_argument("--drain-timeout", type=int, default=300, help="Seconds to await Outbox failure/recovery evidence")
    parser.add_argument("--mysql-exe", type=Path, required=True)
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", type=int, default=33319)
    parser.add_argument("--mysql-user", default="root")
    parser.add_argument("--mysql-password-env", default="CITYCOPILOT_BOOKING_LOAD_DB_PASSWORD")
    parser.add_argument("--database", default="citycopilot_booking_load_test")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    url = urllib.parse.urlparse(args.base_url)
    if url.scheme not in ("http", "https") or url.hostname not in ("localhost", "127.0.0.1", "::1"):
        parser.error("The booking load runner only accepts a loopback Commerce URL")
    if args.mysql_host not in ("localhost", "127.0.0.1", "::1") or not args.database.endswith("_load_test"):
        parser.error("Use a loopback MySQL host and an isolated database ending in _load_test")
    if not args.database.replace("_", "").isalnum() or not args.mysql_exe.is_file():
        parser.error("Invalid database name or MySQL executable")
    if not (1 <= args.users <= 10000 and 1 <= args.concurrency <= 1000 and args.timeout > 0):
        parser.error("users must be 1..10000; concurrency 1..1000; timeout must be positive")
    if args.shop_id <= 0 or args.merchant_id <= 0:
        parser.error("shop-id and merchant-id must be positive existing Core identities")
    if not 10 <= args.drain_timeout <= 1800:
        parser.error("drain-timeout must be 10..1800 seconds")
    if not os.environ.get(args.service_token_env):
        parser.error(f"Required service token environment variable is unset: {args.service_token_env}")
    return args


class Runner:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.run_id = "booking-" + uuid.uuid4().hex[:16]
        self.token = os.environ[args.service_token_env]
        self.resource_ids: list[int] = []

    def compose(self, *arguments: str) -> str:
        compose_file = Path(__file__).with_name("booking-mq-compose.yaml")
        result = subprocess.run([self.args.docker_exe, "compose", "-p", "citycopilot-booking-load", "-f", str(compose_file),
                                 *arguments], capture_output=True, text=True, encoding="utf-8", timeout=180)
        if result.returncode:
            raise RuntimeError("Isolated RocketMQ command failed: " + result.stderr.strip())
        return result.stdout.strip()

    def metrics(self, suffix: str) -> None:
        origin = urllib.parse.urlsplit(self.args.base_url)
        url = urllib.parse.urlunsplit((origin.scheme, origin.netloc, "/actuator/prometheus", "", ""))
        with urllib.request.urlopen(url, timeout=self.args.timeout) as response:
            lines = response.read().decode().splitlines()
        selected = [line for line in lines if line.startswith(("citycopilot_commerce_", "jvm_", "process_", "system_cpu_"))]
        self.args.output.with_suffix(f".{suffix}.prometheus").write_text("\n".join(selected) + "\n", encoding="utf-8")

    def outbox(self) -> dict:
        resources = ",".join(str(value) for value in self.resource_ids)
        result = self.sql(f"""
SELECT JSON_OBJECT('total',COUNT(*),'published',COALESCE(SUM(e.status='PUBLISHED'),0),
 'failed',COALESCE(SUM(e.status='FAILED'),0),'claimed',COALESCE(SUM(e.status='CLAIMED'),0),
 'retried',COALESCE(SUM(e.attempts>1),0),'attempts',COALESCE(SUM(e.attempts),0),
 'firstFailure',MIN(e.last_error)) FROM commerce_outbox_event e
 JOIN commerce_booking b ON b.id=e.aggregate_id
 WHERE e.aggregate_type='commerce_booking' AND b.resource_id IN ({resources});
""")
        return {"at": datetime.now(timezone.utc).isoformat(), **json.loads(result)}

    def await_outbox(self, predicate) -> dict:
        deadline = time.monotonic() + self.args.drain_timeout
        while True:
            snapshot = self.outbox()
            if predicate(snapshot) or time.monotonic() >= deadline:
                return snapshot
            time.sleep(1)

    def expiry_probe(self, start: datetime) -> dict:
        resource = self.resource("COURT", "outage-expiry")
        slot = self.slot(resource, start, 1)
        held = self.required(self.call("POST", "/bookings/holds", self.args.merchant_id,
            {"resourceId": resource["id"], "slotId": slot["id"], "partySize": 2, "source": "LOAD_TEST"},
            self.run_id + ":outage-expiry"))["booking"]
        return {"bookingId": held["id"], "holdExpiresAt": held["holdExpiresAt"]}

    def await_expiry_probe(self, probe: dict) -> dict:
        deadline = datetime.fromisoformat(probe["holdExpiresAt"].replace("Z", "+00:00")) + timedelta(seconds=15)
        while True:
            state = json.loads(self.sql(f"""SELECT JSON_OBJECT('status',b.status,'occupied',s.occupied)
                FROM commerce_booking b JOIN commerce_booking_slot s ON s.id=b.slot_id WHERE b.id='{probe['bookingId']}';"""))
            if state["status"] == "HOLD_EXPIRED" or datetime.now(timezone.utc) >= deadline:
                return {**probe, **state, "observedAt": datetime.now(timezone.utc).isoformat(),
                        "passed": state["status"] == "HOLD_EXPIRED" and state["occupied"] == 0}
            time.sleep(1)

    def sql(self, statement: str) -> str:
        env = os.environ.copy()
        env["MYSQL_PWD"] = os.environ.get(self.args.mysql_password_env, "")
        result = subprocess.run(
            [str(self.args.mysql_exe), "--protocol=TCP", "-h", self.args.mysql_host,
             "-P", str(self.args.mysql_port), "-u", self.args.mysql_user, "-D", self.args.database,
             "--batch", "--raw", "--skip-column-names", "--default-character-set=utf8mb4"],
            input=statement, text=True, encoding="utf-8", capture_output=True, env=env, check=False,
        )
        if result.returncode:
            raise RuntimeError("MySQL verification failed: " + result.stderr.strip())
        return result.stdout.strip()

    def call(self, method: str, path: str, actor: int, body: dict | None = None,
             key: str | None = None) -> dict:
        headers = {"X-CityCopilot-Service-Token": self.token,
                   "X-CityCopilot-User-Id": str(actor), "Content-Type": "application/json"}
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(self.args.base_url.rstrip("/") + path, method=method,
                                         headers=headers, data=None if body is None else json.dumps(body).encode())
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=self.args.timeout) as response:
                payload = json.load(response)
                status = response.status
        except urllib.error.HTTPError as error:
            status = error.code
            raw = error.read()
            try:
                payload = json.loads(raw)
            except ValueError:
                payload = {"code": f"HTTP_{status}"}
        except (OSError, TimeoutError) as error:
            status, payload = 0, {"code": type(error).__name__}
        return {"status": status, "body": payload, "latency_ms": (time.perf_counter() - started) * 1000}

    def required(self, result: dict) -> dict:
        if not 200 <= result["status"] < 300:
            raise RuntimeError(f"Fixture setup failed: HTTP {result['status']} {result['body']}")
        return result["body"]

    def resource(self, kind: str, label: str) -> dict:
        name = f"{self.run_id}-{label}"
        resource = self.required(self.call("POST", f"/admin/shops/{self.args.shop_id}/booking-resources",
            self.args.merchant_id, {"kind": kind, "name": name, "timezone": "Asia/Shanghai", "maxPartySize": 8, "status": "OPEN"}))
        resource_id = int(resource["id"])
        # Prove the API wrote to the exact database selected for invariant checks.
        matching = int(self.sql(f"SELECT COUNT(*) FROM commerce_booking_resource WHERE id={resource_id} AND name='{name}' AND shop_id={self.args.shop_id};"))
        if matching != 1:
            raise RuntimeError("Commerce URL and verification database do not refer to the same fixture; stopped before load")
        self.resource_ids.append(resource_id)
        return resource

    def slot(self, resource: dict, start: datetime, capacity: int) -> dict:
        return self.required(self.call("POST", f"/admin/booking-resources/{resource['id']}/slots",
            self.args.merchant_id, {"startAt": start.isoformat(), "endAt": (start + timedelta(hours=1)).isoformat(),
                                   "capacity": capacity, "status": "OPEN"}))

    def workflow(self, scenario: str, user: int, resource: dict, slot: dict, party: int) -> dict:
        started = time.perf_counter()
        key = f"{self.run_id}:{scenario}:{user}"
        held = self.call("POST", "/bookings/holds", user,
                         {"resourceId": resource["id"], "slotId": slot["id"], "partySize": party,
                          "source": "LOAD_TEST", "requestId": key}, key + ":hold")
        sample = {"scenario": scenario, "user_id": user, "resource_id": resource["id"], "slot_id": slot["id"],
                  "party_size": party, "hold_status": held["status"], "hold_code": held["body"].get("code"),
                  "hold_latency_ms": held["latency_ms"], "confirm_status": None, "confirm_code": None,
                  "confirm_latency_ms": None, "booking_id": None, "outcome": "unexpected"}
        if 200 <= held["status"] < 300 and held["body"].get("booking"):
            booking = held["body"]["booking"]
            sample["booking_id"] = booking["id"]
            confirmed = self.call("POST", f"/bookings/{booking['id']}/confirm", user,
                                  {"expectedVersion": booking["version"]}, key + ":confirm")
            sample.update(confirm_status=confirmed["status"], confirm_code=confirmed["body"].get("code"),
                          confirm_latency_ms=confirmed["latency_ms"])
            if 200 <= confirmed["status"] < 300 and confirmed["body"].get("booking", {}).get("status") == "CONFIRMED":
                sample["outcome"] = "confirmed"
        elif held["status"] == 409 and held["body"].get("code") == "BOOKING_SLOT_FULL" and scenario != "nonhot":
            sample["outcome"] = "capacity_rejected"
        sample["workflow_latency_ms"] = (time.perf_counter() - started) * 1000
        return sample

    def run_scenario(self, scenario: str, users: list[int], start: datetime) -> tuple[dict, list[dict]]:
        if scenario == "nonhot":
            fixtures = []
            for index, _ in enumerate(users):
                resource = self.resource("COURT", f"nonhot-{index}")
                fixtures.append((resource, self.slot(resource, start, 1), 2))
        else:
            resource = self.resource("COURT" if scenario == "hot-court" else "DINING_POOL", scenario)
            slot = self.slot(resource, start, 1 if scenario == "hot-court" else len(users) * 2)
            fixtures = [(resource, slot, 2 if scenario == "hot-court" else index % 8 + 1) for index, _ in enumerate(users)]
        started = time.perf_counter()
        with ThreadPoolExecutor(max_workers=self.args.concurrency) as pool:
            futures = [pool.submit(self.workflow, scenario, user, *fixture) for user, fixture in zip(users, fixtures)]
            samples = [future.result() for future in as_completed(futures)]
        duration = time.perf_counter() - started
        codes: dict[str, int] = {}
        for sample in samples:
            key = str(sample["confirm_code"] or sample["hold_code"])
            codes[key] = codes.get(key, 0) + 1
        confirmations = sum(sample["outcome"] == "confirmed" for sample in samples)
        unexpected = sum(sample["outcome"] == "unexpected" for sample in samples)
        if scenario == "hot-court" and confirmations != 1:
            unexpected += 1
        if scenario == "nonhot" and confirmations != len(users):
            unexpected += 1
        confirmed_units = sum((sample["party_size"] if scenario == "dining" else 1)
                              for sample in samples if sample["outcome"] == "confirmed")
        if scenario == "dining":
            remaining = len(users) * 2 - confirmed_units
            if confirmations == 0 or any(sample["outcome"] == "capacity_rejected" and sample["party_size"] <= remaining for sample in samples):
                unexpected += 1
        http_requests = len(samples) + sum(sample["confirm_status"] is not None for sample in samples)
        report = {"scenario": scenario, "workflows": len(samples), "http_requests": http_requests,
                  "concurrency": self.args.concurrency, "duration_seconds": round(duration, 3),
                  "workflows_per_second": round(len(samples) / duration, 2),
                  "http_requests_per_second": round(http_requests / duration, 2),
                  "confirmed": confirmations, "confirmed_units": confirmed_units,
                  "capacity_rejected": sum(sample["outcome"] == "capacity_rejected" for sample in samples),
                  "unexpected": unexpected, "business_codes": codes,
                  "workflow_latency_ms": latency([sample["workflow_latency_ms"] for sample in samples]),
                  "hold_latency_ms": latency([sample["hold_latency_ms"] for sample in samples]),
                  "confirmation_latency_ms": latency([sample["confirm_latency_ms"] for sample in samples if sample["confirm_latency_ms"] is not None])}
        return report, samples

    def invariants(self) -> dict:
        resources = ",".join(str(value) for value in self.resource_ids)
        result = self.sql(f"""
SELECT JSON_OBJECT(
 'capacityViolations',(SELECT COUNT(*) FROM commerce_booking_slot WHERE resource_id IN ({resources}) AND (occupied<0 OR occupied>capacity)),
 'occupancyMismatches',(SELECT COUNT(*) FROM commerce_booking_slot s LEFT JOIN
     (SELECT slot_id,SUM(units) actual FROM commerce_booking WHERE resource_id IN ({resources})
      AND status IN ('HELD','CONFIRMED','USED','NO_SHOW') GROUP BY slot_id) b ON b.slot_id=s.id
     WHERE s.resource_id IN ({resources}) AND s.occupied<>COALESCE(b.actual,0)),
 'unitMismatches',(SELECT COUNT(*) FROM commerce_booking WHERE resource_id IN ({resources}) AND
     ((resource_kind='COURT' AND units<>1) OR (resource_kind='DINING_POOL' AND units<>party_size))),
 'missingVersionEvents',(SELECT COUNT(*) FROM commerce_booking b WHERE b.resource_id IN ({resources}) AND
     NOT EXISTS (SELECT 1 FROM commerce_outbox_event e WHERE e.aggregate_type='commerce_booking' AND e.aggregate_id=b.id AND e.aggregate_version=b.version)),
 'held',(SELECT COUNT(*) FROM commerce_booking WHERE resource_id IN ({resources}) AND status='HELD'),
 'confirmed',(SELECT COUNT(*) FROM commerce_booking WHERE resource_id IN ({resources}) AND status='CONFIRMED'),
 'unpublishedOutbox',(SELECT COUNT(*) FROM commerce_outbox_event e JOIN commerce_booking b ON b.id=e.aggregate_id
     WHERE e.aggregate_type='commerce_booking' AND b.resource_id IN ({resources}) AND e.status<>'PUBLISHED'));
""")
        return json.loads(result)


def latency(values: list[float]) -> dict:
    return {"samples": len(values), "mean": round(statistics.fmean(values), 2) if values else None,
            "p50": round(percentile(values, .50), 2) if values else None,
            "p95": round(percentile(values, .95), 2) if values else None,
            "p99": round(percentile(values, .99), 2) if values else None,
            "max": round(max(values), 2) if values else None}


def main() -> int:
    args = parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.users_file.open(encoding="utf-8-sig", newline="") as handle:
        users = [int(row[0]) for row in csv.reader(handle) if row and row[0].strip().isdigit()][:args.users]
    if len(users) != args.users or len(set(users)) != len(users) or min(users) <= 0:
        raise ValueError("users-file must contain at least the requested number of distinct positive Core user IDs")
    if args.mq_outage and args.merchant_id in users:
        raise ValueError("MQ outage reserves the merchant identity for an expiry probe; exclude it from the load user CSV")
    runner = Runner(args)
    if int(runner.sql("SELECT COUNT(*) FROM commerce_booking;")):
        raise RuntimeError("Use a fresh isolated *_load_test database; this runner never deletes existing bookings")
    rules = runner.required(runner.call("GET", "/booking-rules", args.merchant_id))
    server_time = datetime.fromisoformat(rules["serverTime"].replace("Z", "+00:00"))
    start = (server_time.astimezone(timezone.utc) + timedelta(days=1, hours=1)).replace(minute=0, second=0, microsecond=0)
    scenarios = ["hot-court", "dining", "nonhot"] if args.scenario == "all" else [args.scenario]
    reports, samples = [], []
    outage = None
    probe = None
    runner.metrics("before")
    try:
        if args.mq_outage:
            runner.compose("stop", "rocketmq-proxy", "rocketmq-broker")
            probe = runner.expiry_probe(start)
        for index, scenario in enumerate(scenarios):
            report, rows = runner.run_scenario(scenario, users, start + timedelta(hours=index * 2))
            reports.append(report)
            samples.extend(rows)
        if args.mq_outage:
            probe = runner.await_expiry_probe(probe)
            outage = {"expiryProbe": probe, "during": runner.await_outbox(lambda state: state["failed"] > 0),
                      "invariantsDuring": runner.invariants()}
    finally:
        if args.mq_outage:
            # The named Compose project is dedicated to this runner. Always restore it after a failed fixture/load.
            runner.compose("up", "-d", "--wait", "rocketmq-broker", "rocketmq-proxy")
    if outage is not None:
        outage["recovery"] = runner.await_outbox(lambda state: state["total"] == state["published"])
        outage["passed"] = (outage["expiryProbe"]["passed"] and outage["during"]["failed"] > 0 and outage["during"]["published"] == 0
                            and outage["recovery"]["retried"] > 0
                            and outage["recovery"]["total"] == outage["recovery"]["published"])
    publication = None
    if args.require_outbox_drain:
        publication = runner.await_outbox(lambda state: state["total"] == state["published"])
    runner.metrics("after")
    invariant = runner.invariants()
    passed = all(report["unexpected"] == 0 for report in reports) and all(invariant[key] == 0 for key in
        ("capacityViolations", "occupancyMismatches", "unitMismatches", "missingVersionEvents", "held"))
    if outage is not None:
        passed = passed and outage["passed"]
    if publication is not None:
        passed = passed and publication["total"] == publication["published"]
    output = {"runId": runner.run_id, "timestamp": datetime.now(timezone.utc).isoformat(), "passed": passed,
              "scope": "Loopback Commerce HTTP with real MySQL and Core-authorized merchant fixtures; not a Core/browser or multi-host capacity claim",
              "baseUrl": args.base_url, "database": args.database, "resourceIds": runner.resource_ids,
              "scenarios": reports, "invariants": invariant, "mqOutage": outage, "publication": publication,
              "client": {"python": platform.python_version(), "platform": platform.platform()}}
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    sample_path = args.output.with_suffix(".samples.jsonl")
    sample_path.write_text("".join(json.dumps(sample, ensure_ascii=False) + "\n" for sample in samples), encoding="utf-8")
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
