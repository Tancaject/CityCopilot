#!/usr/bin/env python3
"""Headless, reproducible seckill load runner using only Python's standard library."""

from __future__ import annotations

import argparse
import csv
import json
import math
import platform
import statistics
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path


def percentile(values: list[float], percent: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(percent * len(ordered)) - 1))
    return ordered[index]


def invoke(url: str, token: str, timeout: float) -> dict[str, object]:
    started = time.perf_counter()
    request = urllib.request.Request(
        url,
        data=b"",
        method="POST",
        headers={"authorization": token, "accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8", errors="replace")
            status = response.status
        payload = json.loads(body) if body else {}
        business_ok = bool(payload.get("success", status < 400))
        code = payload.get("code")
        return {
            "latency_ms": (time.perf_counter() - started) * 1000,
            "http_status": status,
            "ok": status < 400 and business_ok,
            "code": code,
        }
    except urllib.error.HTTPError as error:
        return {
            "latency_ms": (time.perf_counter() - started) * 1000,
            "http_status": error.code,
            "ok": False,
            "code": f"HTTP_{error.code}",
        }
    except Exception as error:  # noqa: BLE001 - the error class is part of the report
        return {
            "latency_ms": (time.perf_counter() - started) * 1000,
            "http_status": 0,
            "ok": False,
            "code": type(error).__name__,
        }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tokens", required=True, type=Path)
    parser.add_argument("--url", default="http://127.0.0.1:8081/voucher-order/seckill/100")
    parser.add_argument("--concurrency", type=int, default=200)
    parser.add_argument("--limit", type=int, default=5000)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    with args.tokens.open(newline="", encoding="utf-8") as handle:
        tokens = [row[0] for row in csv.reader(handle) if row][: args.limit]
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(invoke, args.url, token, args.timeout) for token in tokens]
        results = [future.result() for future in as_completed(futures)]
    duration = time.perf_counter() - started
    latencies = [float(result["latency_ms"]) for result in results]
    successes = sum(1 for result in results if result["ok"])
    errors: dict[str, int] = {}
    for result in results:
        if result["ok"]:
            continue
        code = str(result["code"] or "UNKNOWN")
        errors[code] = errors.get(code, 0) + 1
    report = {
        "scenario": f"POST {args.url} with one authenticated user per request",
        "requests": len(results),
        "concurrency": args.concurrency,
        "duration_seconds": round(duration, 3),
        "throughput_rps": round(len(results) / duration, 2) if duration else 0,
        "successes": successes,
        "errors": len(results) - successes,
        "error_rate": round((len(results) - successes) / len(results), 6) if results else 0,
        "latency_ms": {
            "mean": round(statistics.fmean(latencies), 2) if latencies else 0,
            "p50": round(percentile(latencies, 0.50), 2),
            "p95": round(percentile(latencies, 0.95), 2),
            "p99": round(percentile(latencies, 0.99), 2),
            "max": round(max(latencies), 2) if latencies else 0,
        },
        "error_codes": errors,
        "client": {
            "python": platform.python_version(),
            "platform": platform.platform(),
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["errors"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
