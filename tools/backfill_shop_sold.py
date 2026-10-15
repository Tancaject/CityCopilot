"""一次性回填店铺销量：把 Commerce 现有订单写入 Core 的 shop_order_sales 投影，再按投影重算 shop.sold。

销量口径与 ShopSalesConsumer 一致：已支付(2)、已使用(3)计入。投影版本取订单状态码（与订单事件的
aggregateVersion 相同），之后到达的事件只有版本更高才会覆盖，回填与在线消费不会重复计数。
脚本可重复执行：shop.sold 每次都由投影表整体重算，能修正任何漂移。没有订单的店铺会被置为 0。

用法（仓库根目录）：
    python tools/backfill_shop_sold.py            # 日常 compose 项目
    python tools/backfill_shop_sold.py --project citycopilot-redesign-load
"""
import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BATCH = 1000


def env_file() -> dict:
    values = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def mysql(project: str, service: str, password: str, database: str, sql: str) -> str:
    result = subprocess.run(
        ["docker", "compose", "-p", project, "-f", str(ROOT / "compose.yaml"), "exec", "-T",
         "-e", "MYSQL_PWD=" + password, service, "mysql", "-uroot", "-N", "-B", database],
        input=sql, capture_output=True, text=True, encoding="utf-8", cwd=ROOT)
    if result.returncode:
        raise RuntimeError(f"{service} SQL failed: {result.stderr.strip()}")
    return result.stdout


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project", default="citycopilot")
    parser.add_argument("--core-database", default="citycopilot")
    parser.add_argument("--commerce-database", default="citycopilot_commerce")
    args = parser.parse_args()
    env = env_file()

    rows = [line.split("\t") for line in mysql(
        args.project, "commerce-mysql", env["CITYCOPILOT_COMMERCE_MYSQL_ROOT_PASSWORD"], args.commerce_database,
        "SELECT id, shop_id, status FROM commerce_order;").splitlines() if line]

    statements = ["START TRANSACTION;"]
    for start in range(0, len(rows), BATCH):
        values = ",".join(f"({int(order)},{int(shop)},{int(status)},{int(status)})"
                          for order, shop, status in rows[start:start + BATCH])
        # 先按旧版本决定 status，再推进版本；只有回填的版本更高时才覆盖在线消费已写入的状态。
        statements.append(f"""
            INSERT INTO shop_order_sales(order_id,shop_id,status,aggregate_version) VALUES {values}
            ON DUPLICATE KEY UPDATE
                status=IF(VALUES(aggregate_version)>aggregate_version, VALUES(status), status),
                aggregate_version=GREATEST(aggregate_version, VALUES(aggregate_version));""")
    statements.append("""
        UPDATE shop s
        LEFT JOIN (SELECT shop_id, COUNT(*) sold FROM shop_order_sales WHERE status IN (2,3) GROUP BY shop_id) x
          ON x.shop_id=s.id
        SET s.sold=COALESCE(x.sold,0);
        COMMIT;
        SELECT COUNT(*), COALESCE(SUM(sold),0), COALESCE(SUM(sold>0),0) FROM shop;""")
    summary = mysql(args.project, "mysql", env["CITYCOPILOT_MYSQL_ROOT_PASSWORD"], args.core_database,
                    "\n".join(statements)).split()

    # 店铺详情缓存里还是旧销量：删掉 Redis 副本，本地缓存 5 秒后自然过期。
    subprocess.run(
        ["docker", "compose", "-p", args.project, "-f", str(ROOT / "compose.yaml"), "exec", "-T", "redis", "sh", "-ec",
         'export REDISCLI_AUTH="$REDIS_PASSWORD"; redis-cli --scan --pattern "cache:shop:[0-9]*" | xargs -r redis-cli DEL >/dev/null'],
        check=True, cwd=ROOT)
    print(f"orders imported: {len(rows)}; shops: {summary[0]}, total sold: {summary[1]}, shops with sales: {summary[2]}")


if __name__ == "__main__":
    main()
