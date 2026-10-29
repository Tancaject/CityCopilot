"""逐级加压跑 k6 场景，找吞吐拐点并在每一级采集 MySQL 报告。

每一级：清空 MySQL 语句摘要 → k6 固定到达率跑 --duration → 读取 k6 结果与 MySQL 报告。
p99 超过 --p99-limit 毫秒、服务端错误率超过 1%、或实际吞吐低于目标的 90% 时停止，这一级即拐点。
结果写到 tools/loadtest/results/k6/<label>-<scenario>.json。

用法：
    python tools/loadtest/k6_run.py --label baseline                 # 依次跑客户端上限与五个场景
    python tools/loadtest/k6_run.py --label baseline --scenarios orders --rates 200,500,1000
"""
import argparse
import json
import subprocess
import time
from pathlib import Path

from bigdata_seed import ROOT, env_file

K6_IMAGE = "grafana/k6:2.3.0"
STEPS = {
    "ceiling": [2000, 5000, 10000],
    "browse": [500, 1000, 2000, 3000, 4000, 6000],
    "orders": [200, 500, 1000, 2000, 3000],
    "seckill": [200, 400, 600, 800, 1000],
    "booking": [50, 100, 200, 400, 800],
    "admin": [10, 25, 50, 100, 200],
}
SCRIPTS = {"ceiling": "k6_ceiling.js", "browse": "k6_browse.js", "orders": "k6_orders.js",
           "seckill": "k6_seckill.js", "booking": "k6_booking.js", "admin": "k6_admin.js"}


def run(command: list[str]) -> str:
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", cwd=ROOT)
    if result.returncode:
        raise RuntimeError(f"{command[0]} failed: {result.stderr.strip()[-800:]}")
    return result.stdout


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project", default="citycopilot-bigdata")
    parser.add_argument("--label", required=True)
    parser.add_argument("--scenarios", default=",".join(STEPS))
    parser.add_argument("--rates", help="覆盖默认的逐级到达率，逗号分隔")
    parser.add_argument("--duration", default="60s")
    parser.add_argument("--p99-limit", type=float, default=1000.0)
    args = parser.parse_args()
    env = env_file()
    data = ROOT / "tools/loadtest/results/k6"
    report = ROOT / "tools/loadtest/mysql_report.py"
    for scenario in args.scenarios.split(","):
        rates = [int(r) for r in args.rates.split(",")] if args.rates else STEPS[scenario]
        steps = []
        for rate in rates:
            run(["python", str(report), "--project", args.project, "--reset"])
            started = time.time()
            run(["docker", "run", "--rm", "--network", f"{args.project}_citycopilot",
                 "-v", f"{ROOT / 'tools/loadtest'}:/scripts:ro", "-v", f"{data}:/data",
                 "-e", f"RATE={rate}", "-e", f"DURATION={args.duration}",
                 "-e", f"SERVICE_TOKEN={env['CITYCOPILOT_INTERNAL_COMMERCE_TOKEN']}",
                 K6_IMAGE, "run", "--quiet", f"/scripts/{SCRIPTS[scenario]}"])
            k6 = json.loads((data / f"summary-{scenario}.json").read_text(encoding="utf-8"))
            mysql = {} if scenario == "ceiling" else json.loads(run(["python", str(report), "--project", args.project]))
            step = {"rate": rate, "seconds": round(time.time() - started), "k6": k6, "mysql": mysql}
            steps.append(step)
            latency = k6["latencyMs"]
            knee = (latency["p99"] > args.p99_limit or k6["serverErrorRate"] > 0.01
                    or k6["achievedRps"] < 0.9 * rate)
            print(f"{scenario:8} rate={rate:6} achieved={k6['achievedRps']:8.1f} p50={latency['p50']:7.1f} "
                  f"p95={latency['p95']:7.1f} p99={latency['p99']:7.1f} 5xx={k6['serverErrorRate']:.4f}"
                  f"{'  <- knee' if knee else ''}", flush=True)
            if knee:
                break
        (data / f"{args.label}-{scenario}.json").write_text(
            json.dumps({"scenario": scenario, "label": args.label, "steps": steps}, ensure_ascii=False, indent=2),
            encoding="utf-8")


if __name__ == "__main__":
    main()
