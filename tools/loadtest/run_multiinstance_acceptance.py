#!/usr/bin/env python3
"""Verify the isolated redesign deployment; --failover kills its elected Commerce only.

E2E scenarios: two Core and two Commerce containers share isolated test databases;
nginx distributes requests to both Core instances; one Commerce keeps each periodic
job lease across polling rounds; after its abrupt exit the survivor acquires a higher
fencing token. Container restart happens in finally, including failed assertions.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROJECT = "citycopilot-redesign-load"
COMPOSE = ["docker", "compose", "-p", PROJECT, "-f", str(ROOT / "compose.yaml"),
           "-f", str(ROOT / "tools/loadtest/redesign-compose.yaml"), "--profile", "app"]


def command(args: list[str], data: str | bytes | None = None) -> str:
    result = subprocess.run(args, cwd=ROOT, input=data.encode("utf-8") if isinstance(data, str) else data,
                            capture_output=True, timeout=180)
    if result.returncode:
        raise RuntimeError(f"Command failed ({args[0]} {args[1]}): {result.stderr.decode('utf-8', errors='replace').strip()}")
    return result.stdout.decode("utf-8", errors="replace").strip()


def sql(service: str, query: str) -> str:
    return command(COMPOSE + ["exec", "-T", service, "sh", "-ec",
        'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql -N -B -r -u "$MYSQL_USER" "$MYSQL_DATABASE"'], query)


def inventory() -> dict[str, list[dict]]:
    ids = command(COMPOSE + ["ps", "-q", "core", "commerce", "mysql", "commerce-mysql", "redis",
                            "rocketmq-broker", "rocketmq-proxy"]).splitlines()
    assert ids, "The isolated deployment is not running"
    containers = json.loads(command(["docker", "inspect", *ids]))
    services: dict[str, list[dict]] = {}
    for container in containers:
        labels = container["Config"]["Labels"]
        assert labels.get("com.docker.compose.project") == PROJECT, "Unexpected Compose project"
        service = labels["com.docker.compose.service"]
        services.setdefault(service, []).append(container)
        for mount in container["Mounts"]:
            if mount["Type"] == "volume":
                assert mount["Name"].startswith(PROJECT + "_"), "A business volume is attached"
    assert len(services.get("core", [])) == len(services.get("commerce", [])) == 2, "Exactly two replicas are required"
    for service, database in (("mysql", "citycopilot_core_load_test"),
                              ("commerce-mysql", "citycopilot_commerce_load_test")):
        assert sql(service, "SELECT DATABASE();") == database, "Unexpected verification database"
    return services


def lease() -> dict:
    raw = sql("commerce-mysql", """SELECT JSON_OBJECT('owner',owner_id,'token',fencing_token,
        'held',lease_until>CURRENT_TIMESTAMP(3)) FROM commerce_job_lease
        WHERE job_name='stock-reconciliation';""")
    assert raw, "Reconciliation did not acquire its task lease"
    return json.loads(raw)


def wait_for(predicate, timeout: int):
    deadline = time.monotonic() + timeout
    while True:
        value = predicate()
        if value:
            return value
        assert time.monotonic() < deadline, "Timed out waiting for the expected runtime state"
        time.sleep(1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--failover", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = {"project": PROJECT, "scenarios": {}, "passed": False}
    stopped = None
    try:
        services = inventory()
        for _ in range(12):
            with urllib.request.urlopen("http://127.0.0.1:18090/health", timeout=10) as response:
                assert response.status == 200
        routes = command(COMPOSE + ["exec", "-T", "frontend", "cat", "/var/log/nginx/load-upstreams.log"])
        health_routes = [line.split()[1] for line in routes.splitlines() if line.startswith("/health ")]
        upstreams = set(health_routes[-12:])
        assert len(upstreams) == 2, f"nginx reached {len(upstreams)} Core instances"
        report["scenarios"]["topology"] = {"core": 2, "commerce": 2, "coreUpstreams": sorted(upstreams)}
        before = wait_for(lambda: current if (current := lease())["held"] else None, 45)
        time.sleep(6)
        after = lease()
        assert before == after, "The elected owner/token changed between normal polling rounds"
        report["scenarios"]["stableLeader"] = after
        if args.failover:
            leader = next(container for container in services["commerce"]
                          if after["owner"].startswith(container["Config"]["Hostname"] + ":"))
            stopped = leader["Id"]
            command(["docker", "kill", stopped])
            started = time.monotonic()
            successor = wait_for(lambda: current if (current := lease())["held"]
                                 and current["owner"] != after["owner"] else None, 60)
            assert successor["token"] > after["token"], "The successor did not advance the fencing token"
            report["scenarios"]["leaderFailover"] = {"before": after, "after": successor,
                "observedSeconds": round(time.monotonic() - started, 3)}
        report["passed"] = True
    except Exception as error:
        report["failure"] = str(error)
        raise
    finally:
        if stopped:
            try:
                command(["docker", "start", stopped])
                wait_for(lambda: json.loads(command(["docker", "inspect", "--format", "{{json .State}}", stopped]))
                         .get("Health", {}).get("Status") == "healthy", 150)
            except (RuntimeError, AssertionError) as error:
                report["passed"] = False
                report["restorationFailure"] = str(error)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))
        if report.get("restorationFailure"):
            raise RuntimeError("The isolated Commerce container needs manual restoration; see the report")


if __name__ == "__main__":
    main()
