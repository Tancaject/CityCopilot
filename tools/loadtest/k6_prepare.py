"""为 k6 大数据压测准备夹具：从 bigdata 库按真实热度采样 ID，并把登录令牌写进 Core Redis。

输出 tools/loadtest/results/k6/fixtures.json，供 tools/loadtest/k6/*.js 读取：
- users：令牌池（按下单活跃度加权采样的用户），秒杀场景每次迭代用一个不同用户；
- orderUsers：有订单的用户及其部分订单号、订单总数（订单查询场景用来翻页和查详情）；
- shops：按订单量排序的店铺及权重、坐标（店铺浏览场景按热度抽取）；
- slots：未来仍有余量的预约时段及其资源、价目（预约场景）；
- merchants：热门店铺的店主令牌（后台场景只能查自己的店）；admin：管理员令牌。

用法：python tools/loadtest/k6_prepare.py [--project citycopilot-bigdata] [--users 70000]
"""
import argparse
import json
import random
import subprocess
import uuid
from pathlib import Path

from bigdata_seed import Db, ROOT, env_file


def write_tokens(project: str, tokens: list[tuple[str, int, str]]) -> None:
    """用 redis-cli --pipe 批量写入登录态 Hash，有效期 7 天，跨天的多轮压测不必重新生成。"""
    lines = []
    for token, user_id, role in tokens:
        key = f"login:token:{token}"
        for parts in (("HSET", key, "id", str(user_id), "nickName", f"user{user_id}", "role", role),
                      ("EXPIRE", key, str(7 * 86400))):
            lines.append(f"*{len(parts)}\r\n" + "".join(f"${len(p.encode())}\r\n{p}\r\n" for p in parts))
    result = subprocess.run(
        ["docker", "compose", "-p", project, "-f", str(ROOT / "compose.yaml"), "exec", "-T", "redis", "sh", "-ec",
         'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --pipe'],
        input="".join(lines).encode(), capture_output=True, cwd=ROOT)
    if result.returncode or b"errors: 0" not in result.stdout:
        raise RuntimeError("Redis token pipeline failed: " + result.stdout.decode(errors="replace")[-300:])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project", default="citycopilot-bigdata")
    parser.add_argument("--users", type=int, default=70_000)
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()
    rng = random.Random(args.seed)
    env = env_file()
    core = Db(args.project, "mysql", env["CITYCOPILOT_MYSQL_ROOT_PASSWORD"], "citycopilot")
    commerce = Db(args.project, "commerce-mysql", env["CITYCOPILOT_COMMERCE_MYSQL_ROOT_PASSWORD"], "citycopilot_commerce")

    activity = [(int(u), int(c)) for u, c in commerce.sql(
        "SELECT user_id, COUNT(*) FROM commerce_order GROUP BY user_id")]
    all_users = [int(u) for (u,) in core.sql("SELECT id FROM user_account WHERE role='user'")]
    weighted = {u for u, _ in rng.choices(activity, weights=[c for _, c in activity], k=args.users)}
    while len(weighted) < args.users:  # 不足部分用未下过单的用户补足，秒杀场景需要大量不同用户
        weighted.add(rng.choice(all_users))
    pool = list(weighted)[:args.users]
    users = [{"token": uuid.uuid4().hex, "userId": u} for u in pool]

    counts = dict(activity)
    order_users = [u for u in users if u["userId"] in counts][:5000]
    ids = ",".join(str(u["userId"]) for u in order_users)
    orders = {}
    for user_id, order_id in commerce.sql(
            f"SELECT user_id, id FROM commerce_order WHERE user_id IN ({ids}) ORDER BY create_time DESC"):
        orders.setdefault(int(user_id), []).append(order_id)
    order_fixture = [{"token": u["token"], "userId": u["userId"], "orderCount": counts[u["userId"]],
                      "orders": orders.get(u["userId"], [])[:5]} for u in order_users]

    # 浏览覆盖全部店铺：有订单的店按订单量排在前面，其余随机排后，再按幂律（造数时的店铺热度指数）给权重。
    ranked = [int(s) for s, _ in commerce.sql("SELECT shop_id, COUNT(*) c FROM commerce_order GROUP BY shop_id ORDER BY c DESC")]
    coords = {int(i): (float(x), float(y)) for i, x, y in core.sql("SELECT id, x, y FROM shop")}
    others = [s for s in coords if s not in set(ranked)]
    rng.shuffle(others)
    shops = [{"id": s, "weight": 1 / (rank + 1) ** 0.892, "x": coords[s][0], "y": coords[s][1]}
             for rank, s in enumerate(ranked + others)]

    slots = [{"slotId": s, "resourceId": r, "kind": k, "offerId": o, "free": int(f)} for s, r, k, o, f in commerce.sql("""
        SELECT s.id, s.resource_id, r.kind, o.id, s.capacity - s.occupied FROM commerce_booking_slot s
        JOIN commerce_booking_resource r ON r.id=s.resource_id
        JOIN commerce_booking_offer o ON o.resource_id=r.id AND o.status='OPEN'
        WHERE s.start_at > UTC_TIMESTAMP() + INTERVAL 1 DAY AND s.occupied < s.capacity AND s.status='OPEN'
        ORDER BY RAND(7) LIMIT 50000""")]

    top = [s["id"] for s in shops[:200]]
    owners = {int(s): int(o) for s, o in core.sql(f"SELECT id, owner_user_id FROM shop WHERE id IN ({','.join(map(str, top))})")}
    merchants = [{"token": uuid.uuid4().hex, "userId": owners[s], "shopId": s} for s in top]
    admin = {"token": uuid.uuid4().hex, "userId": 1}

    tokens = [(u["token"], u["userId"], "user") for u in users]
    tokens += [(m["token"], m["userId"], "merchant") for m in merchants] + [(admin["token"], admin["userId"], "admin")]
    write_tokens(args.project, tokens)

    out = ROOT / "tools/loadtest/results/k6"
    out.mkdir(parents=True, exist_ok=True)
    (out / "fixtures.json").write_text(json.dumps({
        "users": users, "orderUsers": order_fixture, "shops": shops, "slots": slots,
        "merchants": merchants, "admin": admin}), encoding="utf-8")
    print(f"users={len(users)} orderUsers={len(order_fixture)} shops={len(shops)} slots={len(slots)} merchants={len(merchants)}")


if __name__ == "__main__":
    main()
