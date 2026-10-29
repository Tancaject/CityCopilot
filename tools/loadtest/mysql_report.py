"""压测场景的 MySQL 观测报告：场景前 --reset 清空语句摘要并记录计数器基线，场景后生成报告。

报告内容（Core 与 Commerce 两个库分别统计，计数器均为本场景内的增量）：
- 按总耗时排序的 SQL 摘要：次数、平均耗时、平均扫描/返回行数、未用索引次数、磁盘临时表；
- 缓冲池命中率、行锁等待、死锁、慢查询条数；
- 应用连接池等待（Hikari 指标）。

用法：
    python tools/loadtest/mysql_report.py --project citycopilot-bigdata --reset
    python tools/loadtest/mysql_report.py --project citycopilot-bigdata --output result.json
"""
import argparse
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATABASES = {
    "core": ("mysql", "citycopilot", "CITYCOPILOT_MYSQL_ROOT_PASSWORD", "core", 8081),
    "commerce": ("commerce-mysql", "citycopilot_commerce", "CITYCOPILOT_COMMERCE_MYSQL_ROOT_PASSWORD", "commerce", 8083),
}
COUNTERS = ("Innodb_buffer_pool_read_requests", "Innodb_buffer_pool_reads", "Innodb_row_lock_waits",
            "Innodb_row_lock_time", "Created_tmp_disk_tables", "Slow_queries")


def env_file() -> dict:
    values = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def compose(project: str, *arguments: str, stdin: str = None) -> str:
    result = subprocess.run(["docker", "compose", "-p", project, "-f", str(ROOT / "compose.yaml"), *arguments],
                            input=stdin, capture_output=True, text=True, encoding="utf-8", cwd=ROOT)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return result.stdout


def mysql(project: str, service: str, password: str, sql: str) -> list[list[str]]:
    output = compose(project, "exec", "-T", "-e", "MYSQL_PWD=" + password, service,
                     "mysql", "-uroot", "-N", "-B", "-e", sql)
    return [line.split("\t") for line in output.splitlines() if line]


def counters(project: str, service: str, password: str) -> dict:
    names = ",".join(f"'{name}'" for name in COUNTERS)
    rows = mysql(project, service, password,
                 f"SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN ({names});")
    values = {name: int(value) for name, value in rows}
    deadlocks = mysql(project, service, password,
                      "SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME='lock_deadlocks';")
    values["deadlocks"] = int(deadlocks[0][0]) if deadlocks else 0
    return values


def reset(project: str, env: dict, state: Path) -> None:
    baseline = {}
    for name, (service, _, password_key, _, _) in DATABASES.items():
        password = env[password_key]
        mysql(project, service, password, "UPDATE performance_schema.setup_instruments SET ENABLED='YES' "
                                          "WHERE NAME LIKE 'wait/lock/innodb%';")
        mysql(project, service, password, "TRUNCATE performance_schema.events_statements_summary_by_digest;")
        baseline[name] = counters(project, service, password)
        baseline[name]["hikariTimeouts"] = hikari(project, DATABASES[name][3], DATABASES[name][4]).get(
            "hikaricp_connections_timeout_total", 0.0)
    state.write_text(json.dumps(baseline), encoding="utf-8")


def hikari(project: str, service: str, port: int) -> dict:
    try:
        text = compose(project, "exec", "-T", service, "curl", "--silent", "--fail",
                       f"http://127.0.0.1:{port}/actuator/prometheus")
    except RuntimeError:
        return {"available": False}
    metrics = {}
    for line in text.splitlines():
        for metric in ("hikaricp_connections_acquire_seconds_max", "hikaricp_connections_pending",
                       "hikaricp_connections_timeout_total", "hikaricp_connections_max"):
            if line.startswith(metric + "{"):
                metrics[metric] = max(metrics.get(metric, 0.0), float(line.rsplit(None, 1)[1]))
    return metrics


def report(project: str, env: dict, state: Path) -> dict:
    baseline = json.loads(state.read_text(encoding="utf-8")) if state.exists() else {}
    result = {}
    for name, (service, schema, password_key, app, port) in DATABASES.items():
        password = env[password_key]
        rows = mysql(project, service, password, f"""
            SELECT LEFT(REPLACE(DIGEST_TEXT, '\\n', ' '), 300), COUNT_STAR,
                   ROUND(SUM_TIMER_WAIT/1e9, 1), ROUND(AVG_TIMER_WAIT/1e9, 3),
                   ROUND(SUM_ROWS_EXAMINED/COUNT_STAR, 1), ROUND(SUM_ROWS_SENT/COUNT_STAR, 1),
                   SUM_NO_INDEX_USED, SUM_CREATED_TMP_DISK_TABLES
            FROM performance_schema.events_statements_summary_by_digest
            WHERE SCHEMA_NAME='{schema}' AND DIGEST_TEXT NOT LIKE 'SELECT @@%'
            ORDER BY SUM_TIMER_WAIT DESC LIMIT 20;""")
        statements = [{"sql": r[0], "count": int(r[1]), "totalMs": float(r[2]), "avgMs": float(r[3]),
                       "rowsExaminedAvg": float(r[4]), "rowsSentAvg": float(r[5]),
                       "noIndexUsed": int(r[6]), "tmpDiskTables": int(r[7])} for r in rows]
        now = counters(project, service, password)
        before = baseline.get(name, {key: 0 for key in now})
        delta = {key: now[key] - before.get(key, 0) for key in now}
        requests = delta["Innodb_buffer_pool_read_requests"]
        # 连接池超时是应用启动以来的累计值，换算成本场景内的增量；获取耗时最大值是最近采样窗口内的值。
        pool = hikari(project, app, port)
        if "hikaricp_connections_timeout_total" in pool:
            pool["timeoutsInScenario"] = pool.pop("hikaricp_connections_timeout_total") - before.get("hikariTimeouts", 0.0)
        result[name] = {
            "bufferPoolHitPct": round(100 * (1 - delta["Innodb_buffer_pool_reads"] / requests), 3) if requests else None,
            "diskReads": delta["Innodb_buffer_pool_reads"],
            "rowLockWaits": delta["Innodb_row_lock_waits"],
            "rowLockTimeMs": delta["Innodb_row_lock_time"],
            "deadlocks": delta["deadlocks"],
            "slowQueries": delta["Slow_queries"],
            "tmpDiskTables": delta["Created_tmp_disk_tables"],
            "connectionPool": pool,
            "topStatements": statements,
        }
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project", default="citycopilot-bigdata")
    parser.add_argument("--reset", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    env = env_file()
    state = ROOT / "tools" / "loadtest" / "results" / f".mysql-report-{args.project}.json"
    state.parent.mkdir(parents=True, exist_ok=True)
    if args.reset:
        reset(args.project, env, state)
        return
    data = report(args.project, env, state)
    text = json.dumps(data, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)


if __name__ == "__main__":
    main()
