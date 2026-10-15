#!/usr/bin/env python3
"""Restart one explicitly identified loopback Commerce test JVM and observe real HOLD expiry."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

from run_booking_api_acceptance import Api, identity, instant, require, test_login


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def mysql_rows(args, sql: str):
    require(sql.startswith("SELECT "), "Database access in this experiment is read-only")
    result = subprocess.run([args.mysql_client, "--protocol=TCP", "--host=127.0.0.1", "--port=33319",
                             "--user=root", "--init-command=SET SESSION time_zone='+00:00'", "--batch", "--raw", "--skip-column-names", "-e", sql],
                            capture_output=True, text=True, check=True)
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


def message_rows(args, booking_id: str):
    require(str(uuid.UUID(booking_id)) == booking_id, "Expected a canonical test booking ID")
    return mysql_rows(args, "SELECT JSON_OBJECT('eventId',o.event_id,'eventType',o.event_type,'version',o.aggregate_version,"
                      "'outboxStatus',o.status,'businessOccurredAt',JSON_UNQUOTE(JSON_EXTRACT(o.payload_json,'$.occurredAt')),"
                      "'publishedAt',DATE_FORMAT(o.published_at,'%Y-%m-%dT%H:%i:%s.%fZ')) "
                      "FROM citycopilot_booking_ui_test.commerce_outbox_event o "
                      f"WHERE o.aggregate_id='{booking_id}' ORDER BY o.aggregate_version")


def observe_delivery(args, booking_id: str):
    deadline = time.monotonic() + args.mq_observation_seconds
    while True:
        events = message_rows(args, booking_id)
        if len(events) == 2 and all(e["outboxStatus"] == "PUBLISHED" for e in events):
            return events
        require(time.monotonic() < deadline, "Commerce did not publish both restart experiment events")
        time.sleep(2)


def verify_report(args) -> dict:
    require(args.test_only, "Report verification is restricted to the isolated test databases")
    report = json.loads(Path(args.verify_report).read_text(encoding="utf-8"))
    require(report.get("failure", {}).get("message") == "Commerce did not publish both restart experiment events",
            "Only an MQ observation timeout can be completed by read-only report verification")
    require(report["restart"]["newPid"] != report["restart"]["oldPid"]
            and report["beforeRestart"]["status"] == report["afterRestartBeforeExpiry"]["status"] == "HELD"
            and report["beforeRestart"]["holdExpiresAt"] == report["afterRestartBeforeExpiry"]["holdExpiresAt"]
            and report["afterExpiry"]["status"] == "HOLD_EXPIRED" and report["afterExpiry"]["occupied"] == 0
            and all(report["cleanup"].values()), "The original restart and cleanup evidence must already have passed")
    booking_id = report["fixture"]["bookingId"]
    events = observe_delivery(args, booking_id)
    require([(e["eventType"], e["version"]) for e in events] == [("commerce.booking.held", 0), ("commerce.booking.hold_expired", 1)], "Unexpected lifecycle event contract")
    jar = Path(__file__).resolve().parents[2] / report["jar"]["path"]
    require(hashlib.sha256(jar.read_bytes()).hexdigest() == report["jar"]["sha256"], "The copied Commerce JAR changed")
    report["initialObservation"] = {"passed": False, "finishedAt": report["finishedAt"], "mqObservationSeconds": report.get("mqObservationSeconds", 35), "failure": report.pop("failure")}
    report["messageDelivery"] = events
    report["jar"]["unchanged"] = True
    report["extendedObservation"] = {"readOnly": True, "observedAt": utc_now(), "maxWaitSeconds": args.mq_observation_seconds}
    report["passed"] = True
    report["limitations"].append("The initial 35-second MQ observation timed out; later read-only verification confirmed delayed delivery. This is not real-time synchronization.")
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


def run(args) -> dict:
    require(args.test_only and os.name == "nt", "This experiment requires an explicitly authorized Windows test JVM")
    root = Path(__file__).resolve().parents[2]
    runtime = root / ".tools/booking-runtime"
    jar = runtime / "commerce-app.jar"
    pid_file = runtime / "commerce.pid"
    require(int(pid_file.read_text().strip()) == args.expected_pid, "PID file differs from the explicitly authorized process")
    jar_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
    run_id = "restart-" + uuid.uuid4().hex
    report = {"testOnly": True, "runId": run_id, "startedAt": utc_now(), "passed": False,
              "environment": {"core": "http://127.0.0.1:8081", "commerce": "http://127.0.0.1:8083",
                              "mysql": "127.0.0.1:33319", "commerceDatabase": "citycopilot_booking_ui_test"},
              "jar": {"path": ".tools/booking-runtime/commerce-app.jar", "sha256": jar_hash},
              "mqObservationSeconds": args.mq_observation_seconds,
              "limitations": ["One isolated local JVM restart; no production recovery-time or availability claim.",
                              "Only SELECT statements inspect MySQL; no deadline or worker policy is rewritten."]}
    api = Api("http://127.0.0.1:8081", 10)

    def powershell(source: str):
        # A detached Windows JVM can inherit the parent's pipe handles and keep capture_output waiting for EOF.
        # Files let us wait for PowerShell itself without waiting for the intentionally persistent JVM.
        operation = runtime / (run_id + "-" + uuid.uuid4().hex)
        stdout_path, stderr_path = operation.with_suffix(".out"), operation.with_suffix(".err")
        with stdout_path.open("w", encoding="utf-8") as stdout, stderr_path.open("w", encoding="utf-8") as stderr:
            result = subprocess.run([args.powershell, "-NoProfile", "-Command", source], cwd=root,
                                    stdout=stdout, stderr=stderr, text=True)
        require(result.returncode == 0, "PowerShell process operation failed: " + stderr_path.read_text(encoding="utf-8").strip())
        return json.loads(stdout_path.read_text(encoding="utf-8"))

    def db(sql: str):
        return mysql_rows(args, sql)

    old = powershell(f"$ErrorActionPreference='Stop'; Get-CimInstance Win32_Process -Filter 'ProcessId = {args.expected_pid}' | Select-Object ProcessId,ExecutablePath,CommandLine,CreationDate | ConvertTo-Json -Compress")
    expected_jar_arg = "-jar .tools/booking-runtime/commerce-app.jar"
    require(old and old["ProcessId"] == args.expected_pid and expected_jar_arg in old["CommandLine"], "Authorized PID is not the copied Commerce JVM")
    require("--spring.config.additional-location=file:.tools/booking-runtime/commerce.yaml" in old["CommandLine"], "Unexpected Commerce profile")
    require("--citycopilot.commerce.outbox-relay-enabled=true" in old["CommandLine"]
            and "--citycopilot.commerce.kafka-enabled=true" in old["CommandLine"], "Real Outbox and MQ must remain enabled")
    report["oldProcess"] = old
    consumer = os.environ.get("CITYCOPILOT_BOOKING_RESTART_TOKEN") or test_login(api, "19900100297", 61380)
    merchant = os.environ.get("CITYCOPILOT_BOOKING_RESTART_MERCHANT_TOKEN") or test_login(api, "19900000900", 61380)
    require(str(identity(api, consumer)["id"]) == "100297", "Consumer must be isolated test user 100297")
    resource = booking = None
    hold_key = run_id + ":hold"

    def checked(method, path, body=None, token=consumer, key=None):
        response = api.call(method, path, body, token, key)
        require(200 <= response["status"] < 300, f"{method} {path} failed with HTTP {response['status']}")
        return response["body"]

    def booking_state():
        return db("SELECT JSON_OBJECT('status',b.status,'version',b.version,'scheduleVersion',b.schedule_version,"
                  "'holdExpiresAt',DATE_FORMAT(b.hold_expires_at,'%Y-%m-%dT%H:%i:%s.%fZ'),"
                  "'databaseNow',DATE_FORMAT(UTC_TIMESTAMP(3),'%Y-%m-%dT%H:%i:%s.%fZ'),'occupied',s.occupied) "
                  "FROM citycopilot_booking_ui_test.commerce_booking b JOIN citycopilot_booking_ui_test.commerce_booking_slot s ON s.id=b.slot_id "
                  f"WHERE b.id='{booking['id']}'")[0]

    try:
        rules = checked("GET", "/booking-rules")
        require(rules["holdSeconds"] == 120, "Experiment requires the actual 120-second hold policy")
        report["effectiveHoldSeconds"] = rules["holdSeconds"]
        resource = checked("POST", "/admin/shops/90002/booking-resources",
                           {"kind": "DINING_POOL", "name": run_id, "timezone": "Asia/Shanghai", "maxPartySize": 2, "status": "OPEN"}, merchant)
        start = (datetime.now(timezone.utc) + timedelta(days=1)).replace(hour=10, minute=0, second=0, microsecond=0)
        slot = checked("POST", f"/admin/booking-resources/{resource['id']}/slots",
                       {"startAt": start.isoformat(), "endAt": (start + timedelta(hours=1)).isoformat(), "capacity": 2, "status": "OPEN"}, merchant)
        held = checked("POST", "/bookings/holds", {"resourceId": resource["id"], "slotId": slot["id"], "partySize": 2,
                       "source": "RESTART_ACCEPTANCE", "requestId": run_id}, key=hold_key)
        booking = held["booking"]
        before = booking_state()
        require(before["status"] == "HELD" and before["occupied"] == 2, "HOLD must be committed before the process stops")
        remaining = (instant(before["holdExpiresAt"]) - instant(before["databaseNow"])).total_seconds()
        require(110 <= remaining <= 120, "Persisted deadline must be the real 120-second policy")
        report["fixture"] = {"userId": "100297", "shopId": "90002", "resourceId": resource["id"], "slotId": slot["id"], "bookingId": booking["id"], "pricingMode": "ON_SITE"}
        report["beforeRestart"] = before
        print(json.dumps({"stage": "committedHold", "bookingId": booking["id"], "holdExpiresAt": before["holdExpiresAt"]}), flush=True)
        # Recheck identity immediately before stopping, and preserve the original argument string exactly.
        restart = powershell(f"""
$ErrorActionPreference='Stop'
$restartProc=Get-CimInstance Win32_Process -Filter 'ProcessId = {args.expected_pid}'
if (!$restartProc -or $restartProc.CommandLine -notlike '*-jar .tools/booking-runtime/commerce-app.jar*') {{ throw 'Commerce identity changed' }}
if ([int](Get-Content -LiteralPath '.tools/booking-runtime/commerce.pid') -ne {args.expected_pid}) {{ throw 'Commerce PID file changed' }}
$prefix='"'+$restartProc.ExecutablePath+'" '
if (!$restartProc.CommandLine.StartsWith($prefix)) {{ throw 'Unexpected Java executable quoting' }}
$restartArgs=$restartProc.CommandLine.Substring($prefix.Length)
$stoppedAt=[DateTimeOffset]::UtcNow.ToString('o')
Stop-Process -Id {args.expected_pid} -Force -ErrorAction Stop
Wait-Process -Id {args.expected_pid} -ErrorAction SilentlyContinue
$replacement=Start-Process -FilePath $restartProc.ExecutablePath -ArgumentList $restartArgs -WorkingDirectory (Get-Location).Path -WindowStyle Hidden -RedirectStandardOutput '.tools/booking-runtime/commerce-restart.out.log' -RedirectStandardError '.tools/booking-runtime/commerce-restart.err.log' -PassThru
Set-Content -LiteralPath '.tools/booking-runtime/commerce.pid' -Value $replacement.Id -Encoding ascii
@{{oldPid={args.expected_pid};newPid=$replacement.Id;stoppedAt=$stoppedAt;startedAt=[DateTimeOffset]::UtcNow.ToString('o');argumentsPreserved=$true}} | ConvertTo-Json -Compress
""")
        report["restart"] = restart
        require(restart["newPid"] != args.expected_pid, "Restart must create a different JVM process")
        started_wait = time.monotonic()
        while time.monotonic() - started_wait < 60:
            response = api.call("GET", "/booking-rules")
            if response["status"] == 200:
                break
            time.sleep(1)
        else:
            raise AssertionError("Core could not reach the restarted Commerce within 60 seconds")
        report["restart"]["coreObservedReadyAt"] = utc_now()
        after = booking_state()
        require(after["status"] == "HELD" and after["occupied"] == 2 and after["holdExpiresAt"] == before["holdExpiresAt"],
                "Restart did not preserve the active HOLD and original deadline")
        report["afterRestartBeforeExpiry"] = after
        print(json.dumps({"stage": "restarted", "newPid": restart["newPid"], "status": after["status"]}), flush=True)
        deadline = instant(before["holdExpiresAt"])
        while datetime.now(timezone.utc) < deadline + timedelta(seconds=35):
            observed = booking_state()
            if observed["status"] == "HOLD_EXPIRED":
                break
            require(observed["status"] == "HELD", "Unexpected booking transition during expiry observation")
            time.sleep(2)
        require(observed["status"] == "HOLD_EXPIRED" and observed["occupied"] == 0, "Worker did not expire the persisted HOLD and release capacity")
        require(instant(observed["databaseNow"]) >= deadline, "Expiry occurred before the original real deadline")
        report["afterExpiry"] = observed
        report["observedExpiryDelayMs"] = round((instant(observed["databaseNow"]) - deadline).total_seconds() * 1000)
        events = observe_delivery(args, booking["id"])
        require([(e["eventType"], e["version"]) for e in events] == [("commerce.booking.held", 0), ("commerce.booking.hold_expired", 1)], "Unexpected lifecycle event contract")
        report["messageDelivery"] = events
        require(hashlib.sha256(jar.read_bytes()).hexdigest() == jar_hash, "The copied Commerce JAR changed during the experiment")
        report["jar"]["unchanged"] = True
        report["passed"] = True
    except Exception as error:
        report["failure"] = {"type": type(error).__name__, "message": str(error)}
    finally:
        cleanup = {"bookingReleased": booking is None, "resourceUnavailable": resource is None}
        if booking is None:
            recovered = api.call("GET", "/booking-commands/" + hold_key, token=consumer)
            if recovered["status"] == 200 and recovered["body"].get("bookingId"):
                booking = {"id": recovered["body"]["bookingId"]}
        if booking:
            current = api.call("GET", "/bookings/" + booking["id"], token=consumer)
            if current["status"] == 200 and current["body"]["status"] in ("HELD", "CONFIRMED"):
                api.call("POST", f"/bookings/{booking['id']}/cancel", {"expectedVersion": current["body"]["version"], "reason": "Restart acceptance cleanup"}, consumer, run_id + ":cleanup")
            cleanup["bookingReleased"] = booking_state()["occupied"] == 0
        if resource:
            closed = api.call("PUT", f"/admin/booking-resources/{resource['id']}",
                              {"kind": resource["kind"], "name": resource["name"], "timezone": resource["timezone"], "maxPartySize": resource["maxPartySize"],
                               "status": "UNAVAILABLE", "expectedVersion": resource["version"]}, merchant)
            cleanup["resourceUnavailable"] = closed["status"] == 200
        report["cleanup"] = cleanup
        report["passed"] = report["passed"] and all(cleanup.values())
        report["finishedAt"] = utc_now()
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test-only", action="store_true")
    parser.add_argument("--expected-pid", type=int, help="Explicitly authorized current commerce.pid value; required for a new restart")
    parser.add_argument("--powershell", default="pwsh")
    parser.add_argument("--mysql-client", default="C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe")
    parser.add_argument("--output", required=True)
    parser.add_argument("--mq-observation-seconds", type=int, default=180, help="Bounded read-only MQ observation; actual latency is retained in the report")
    parser.add_argument("--verify-report", help="Complete only a previous report's MQ observation; does not restart or write business state")
    args = parser.parse_args()
    require(1 <= args.mq_observation_seconds <= 600, "MQ observation must remain bounded")
    require(args.verify_report or args.expected_pid, "A new restart requires an explicitly authorized PID")
    report = verify_report(args) if args.verify_report else run(args)
    print(json.dumps({"passed": report["passed"], "cleanup": report["cleanup"], "failure": report.get("failure")}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
