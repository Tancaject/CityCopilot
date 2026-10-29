"""按接近真实的分布造海量数据，供 MySQL 优化实验使用（独立项目 citycopilot-bigdata，不碰日常数据）。

规模以完整版为基准乘 --scale（默认 0.1，即订单 100 万）。分布假设：
- 店铺热度、用户活跃度为幂律：前 1% 店铺约占 40% 订单，前 10% 用户约占 50% 订单（指数自动求解）。
- 秒杀券全部是已结束并归档的历史活动；其中 10 张爆款，买家数从 50 万×scale 线性降到 10 万×scale。
- 订单状态：已使用 58%、已支付未使用 15%、超时取消 21%、退款 6%；历史数据里没有待支付订单（真实系统 15 分钟内就会超时取消）。
- 活动时间分布在最近 365 天，最近 30 天占 20%；下单集中在活动开头。
- 预约：每个资源 150 个每日时段（过去 120 天、未来 30 天），场馆:餐厅 = 6:4，占用数与有效预约人数一致。
数据生成成文本文件后用 LOAD DATA 导入，保留唯一约束检查，由数据库校验数据合法；时间统一写 UTC。

用法（先启动 bigdata 项目的 MySQL 与迁移）：
    python tools/loadtest/bigdata_seed.py               # 造数 + 导入 + 校验
    python tools/loadtest/bigdata_seed.py --verify-only # 只跑校验报告
"""
import argparse
import bisect
import hashlib
import json
import random
import subprocess
import time
import uuid
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FULL = {"users": 1_000_000, "shops": 100_000, "notes": 500_000, "likes": 3_000_000, "follows": 2_000_000,
        "vouchers": 50_000, "seckill": 5_000, "orders": 10_000_000, "outbox": 5_000_000, "resources": 20_000}
HOT_VOUCHERS = 10
AUDIT_GROUP = "citycopilot-event-audit-v1"
TOPIC = "citycopilot-commerce-events-v1"
NOW = datetime.now(timezone.utc).replace(microsecond=0)
STATUS_EVENTS = {2: ["pay"], 3: ["pay", "use"], 4: ["cancel"], 6: ["pay", "refund"]}
ACTION_STATE = {"pay": 2, "use": 3, "cancel": 4, "refund": 6}


def env_file() -> dict:
    values = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


class Db:
    def __init__(self, project: str, service: str, password: str, schema: str):
        self.project, self.service, self.password, self.schema = project, service, password, schema
        self.container = self.compose("ps", "-q", service).strip()
        if not self.container:
            raise RuntimeError(f"{service} is not running in project {project}")

    def compose(self, *arguments: str, stdin: str = None) -> str:
        result = subprocess.run(["docker", "compose", "-p", self.project, "-f", str(ROOT / "compose.yaml"), *arguments],
                                input=stdin, capture_output=True, text=True, encoding="utf-8", cwd=ROOT)
        if result.returncode:
            raise RuntimeError(result.stderr.strip())
        return result.stdout

    def sql(self, statement: str) -> list[list[str]]:
        output = self.compose("exec", "-T", "-e", "MYSQL_PWD=" + self.password, self.service,
                              "mysql", "-uroot", "-N", "-B", self.schema, stdin=statement)
        return [line.split("\t") for line in output.splitlines() if line]

    def load(self, table: str, columns: list[str], path: Path) -> float:
        started = time.perf_counter()
        target = f"/var/lib/mysql-files/{path.name}"
        subprocess.run(["docker", "cp", str(path), f"{self.container}:{target}"], check=True, capture_output=True)
        try:
            # 外键按依赖顺序导入，可安全关闭检查；唯一约束保持开启，让数据库校验生成的数据。
            self.sql(f"""SET foreign_key_checks=0; SET time_zone='+00:00';
                LOAD DATA INFILE '{target}' INTO TABLE {table} CHARACTER SET utf8mb4
                FIELDS TERMINATED BY '\\t' LINES TERMINATED BY '\\n' ({",".join(columns)});""")
        finally:
            subprocess.run(["docker", "exec", self.container, "rm", "-f", target], capture_output=True)
        return time.perf_counter() - started


class Writer:
    """把行写成 LOAD DATA 的制表符格式；None 写 \\N。"""

    def __init__(self, directory: Path, name: str):
        self.path = directory / f"{name}.tsv"
        self.file = self.path.open("w", encoding="utf-8", newline="\n")
        self.rows = 0

    def row(self, *values) -> None:
        self.file.write("\t".join("\\N" if v is None else str(v) for v in values) + "\n")
        self.rows += 1

    def close(self) -> Path:
        self.file.close()
        return self.path


def ts(value: datetime) -> str:
    return value.strftime("%Y-%m-%d %H:%M:%S.") + f"{value.microsecond // 1000:03d}"


def zipf_exponent(n: int, top_fraction: float, target_share: float) -> float:
    """二分求幂律指数，使前 top_fraction 的元素占 target_share 的权重。"""
    low, high = 0.0, 3.0
    top = max(1, int(n * top_fraction))
    for _ in range(40):
        s = (low + high) / 2
        weights = [1 / (i + 1) ** s for i in range(n)]
        share = sum(weights[:top]) / sum(weights)
        low, high = (s, high) if share < target_share else (low, s)
    return (low + high) / 2


def top_share(counts, population: int, fraction: float) -> float:
    ordered = sorted(counts.values(), reverse=True)
    return sum(ordered[:max(1, int(population * fraction))]) / max(1, sum(ordered))


def calibrate(share_of, target: float) -> float:
    """二分求指数，使模拟出的集中度命中目标；集中度随指数单调上升。"""
    low, high = 0.0, 2.5
    for _ in range(10):
        s = (low + high) / 2
        low, high = (s, high) if share_of(s) < target else (low, s)
    return (low + high) / 2


def _assign(rng, shop_ids, s, voucher_ids, sizes) -> dict:
    pick = Zipf(rng, shop_ids, s)
    counts = {}
    for vid in voucher_ids:
        shop = pick.one()
        counts[shop] = counts.get(shop, 0) + sizes[vid]
    return counts


def _user_orders(rng, users, s, sizes) -> dict:
    pick = Zipf(rng, users, s)
    counts = {}
    for size in sizes.values():
        for uid in pick.distinct(min(size, len(users))):
            counts[uid] = counts.get(uid, 0) + 1
    return counts


class Zipf:
    """按幂律权重抽样；排名打乱后映射到 ID，热门 ID 不集中在小号段。"""

    def __init__(self, rng: random.Random, ids: list[int], s: float):
        self.rng = rng
        self.ids = ids[:]
        rng.shuffle(self.ids)
        total, self.cumulative = 0.0, []
        for i in range(len(ids)):
            total += 1 / (i + 1) ** s
            self.cumulative.append(total)

    def one(self) -> int:
        return self.ids[bisect.bisect_left(self.cumulative, self.rng.random() * self.cumulative[-1])]

    def distinct(self, k: int) -> list[int]:
        chosen = set()
        for _ in range(4):
            chosen.update(self.rng.choices(self.ids, cum_weights=self.cumulative, k=(k - len(chosen)) * 2))
            if len(chosen) >= k:
                break
        while len(chosen) < k:  # 热门集合抽满后，用均匀抽样补足不重复的长尾
            chosen.add(self.rng.choice(self.ids))
        return list(chosen)[:k]


def generate(args, out: Path) -> dict:
    rng = random.Random(args.seed)
    n = {key: max(1, int(value * args.scale)) for key, value in FULL.items()}
    users = list(range(1, n["users"] + 1))
    merchants = max(1, n["shops"] // 4)
    shop_ids = list(range(1, n["shops"] + 1))
    shop_s = zipf_exponent(n["shops"], 0.01, 0.40)
    shop_pick = Zipf(rng, shop_ids, shop_s)
    files = {}

    # ---------- Core：用户、店铺 ----------
    w = Writer(out, "user_account")
    for uid in users:
        w.row(uid, f"13{uid:09d}", f"user{uid}", ts(NOW - timedelta(days=rng.uniform(1, 730))),
              "merchant" if uid <= merchants else "user")
    files["user_account"] = (w.close(), ["id", "phone", "nick_name", "create_time", "role"], "core")

    centers = [(121.47 + rng.uniform(-0.25, 0.25), 31.23 + rng.uniform(-0.2, 0.2)) for _ in range(20)]
    shop_owner = {}
    shop_writer = Writer(out, "shop")
    shop_rows = []
    for sid in shop_ids:
        cx, cy = rng.choice(centers)
        shop_owner[sid] = rng.randint(1, merchants)
        shop_rows.append([sid, f"店铺{sid}", rng.randint(1, 10), shop_owner[sid], "seed.png", "上海",
                          f"上海市某路{sid}号", round(cx + rng.gauss(0, 0.03), 6), round(cy + rng.gauss(0, 0.03), 6),
                          rng.randint(30, 300), 0, rng.randint(0, 500), rng.randint(30, 50), "10:00-22:00",
                          ts(NOW - timedelta(days=rng.uniform(30, 900))), "seed", "verified"])

    # ---------- Commerce：券 ----------
    seckill_ids = list(range(1, n["seckill"] + 1))
    voucher_shop = {vid: shop_pick.one() for vid in range(1, n["vouchers"] + 1)}
    hot = set(rng.sample(seckill_ids, min(HOT_VOUCHERS, len(seckill_ids))))
    hot_sizes = [int((500_000 - 400_000 * i / max(1, HOT_VOUCHERS - 1)) * args.scale) for i in range(HOT_VOUCHERS)]
    voucher_buyers = {vid: size for vid, size in zip(sorted(hot), hot_sizes)}
    rest = [vid for vid in seckill_ids if vid not in hot]
    remaining = max(0, n["orders"] - sum(voucher_buyers.values()))
    weights = [1 / (i + 1) ** 0.8 for i in range(len(rest))]
    rng.shuffle(rest)
    cap = max(1, int(n["users"] * 0.09))
    open_weights = dict(zip(rest, weights))
    left = remaining
    while left > len(open_weights) and open_weights:  # 触顶的券把余量让给其余券，订单总数贴近目标
        total_weight = sum(open_weights.values())
        for vid, weight in list(open_weights.items()):
            add = min(cap - voucher_buyers.get(vid, 0), int(left * weight / total_weight))
            voucher_buyers[vid] = voucher_buyers.get(vid, 0) + add
            if voucher_buyers[vid] >= cap:
                del open_weights[vid]
        left = remaining - sum(voucher_buyers[v] for v in rest)
        if left <= len(open_weights):
            break
    for vid in rest:
        voucher_buyers[vid] = max(1, voucher_buyers.get(vid, 0))
    # 秒杀券归属店铺、用户活跃度的幂律指数按真实分配规则模拟校准：券量与“每人每券一单”会改变集中度。
    seckill_shop_s = calibrate(lambda s: top_share(Counter(
        _assign(random.Random(args.seed + 1), shop_ids, s, seckill_ids, voucher_buyers)), n["shops"], 0.01), 0.40)
    user_s = calibrate(lambda s: top_share(_user_orders(random.Random(args.seed + 2), users, s, voucher_buyers),
                                           n["users"], 0.10), 0.50)
    user_pick = Zipf(rng, users, user_s)
    seckill_shop_pick = Zipf(rng, shop_ids, seckill_shop_s)
    for vid in seckill_ids:
        voucher_shop[vid] = seckill_shop_pick.one()
    w = Writer(out, "commerce_voucher")
    for vid, sid in voucher_shop.items():
        seckill = vid <= n["seckill"]
        w.row(vid, sid, f"{'秒杀券' if seckill else '代金券'}{vid}", "限时特惠", "每人限购一张",
              rng.choice([990, 1990, 2990, 4990]), rng.choice([5000, 10000, 20000]), 1 if seckill else 0, 1,
              ts(NOW - timedelta(days=rng.uniform(1, 400))))
    files["commerce_voucher"] = (w.close(), ["id", "shop_id", "title", "sub_title", "rules", "pay_value",
                                             "actual_value", "type", "status", "create_time"], "commerce")


    # ---------- 订单及其账本、预留、恢复、支付、事件 ----------
    order_files = {name: Writer(out, name) for name in (
        "commerce_order", "commerce_reservation", "commerce_admission_ledger", "commerce_order_recovery",
        "commerce_payment", "commerce_seckill_voucher", "processed_event", "event_projection_version",
        "shop_order_sales")}
    sold = {}
    orders_for_outbox = []
    oid = 1_000_000
    for vid in seckill_ids:
        recent = rng.random() < 0.2
        begin = NOW - timedelta(days=rng.uniform(2, 30) if recent else rng.uniform(30, 365))
        duration = timedelta(days=rng.uniform(1, 3))
        end = min(begin + duration, NOW - timedelta(days=1, hours=1))
        active = 0
        sid = voucher_shop[vid]
        for uid in user_pick.distinct(min(voucher_buyers[vid], len(users))):
            oid += 1
            r = rng.random()
            status = 3 if r < 0.58 else 2 if r < 0.73 else 4 if r < 0.94 else 6
            created = begin + (end - begin) * rng.random() ** 3
            pay = created + timedelta(seconds=rng.uniform(30, 600)) if status in (2, 3, 6) else None
            used = min(pay + timedelta(hours=rng.uniform(1, 168)), NOW) if status == 3 else None
            refund = min(pay + timedelta(hours=rng.uniform(1, 72)), NOW) if status == 6 else None
            version = len(STATUS_EVENTS[status])
            key = f"bd-{oid}"
            active += status in (2, 3)
            order_files["commerce_order"].row(
                oid, uid, vid, sid, f"店铺{sid}", f"秒杀券{vid}", "限时特惠", "每人限购一张", 1990, 5000,
                1 if pay else None, status, version, key, ts(created), ts(pay) if pay else None,
                ts(used) if used else None, ts(refund) if refund else None, ts(created + timedelta(minutes=15)),
                "PAY_TIMEOUT" if status == 4 else None)
            source = f"0:1:0:{oid}-0"
            order_files["commerce_reservation"].row(oid, vid, uid, "ORDER_CREATED" if status in (2, 3) else "CANCELLED",
                                                    version, source, ts(created))
            order_files["commerce_admission_ledger"].row(oid, uid, key, vid, f"seckill:v1:{vid}", "v1", 1, "ORDER_CREATED",
                                                         ts(created), ts(created + timedelta(seconds=1)), ts(created))
            order_files["commerce_order_recovery"].row(
                source, "STREAM_ORDER", oid, vid, uid, key,
                json.dumps({"orderId": str(oid), "voucherId": str(vid), "userId": str(uid), "idempotencyKey": key}),
                "ORDER_CREATED", ts(created + timedelta(seconds=1)), ts(created + timedelta(seconds=1)), ts(created))
            if status in (4, 6):
                action = "cancel" if status == 4 else "refund"
                order_files["commerce_order_recovery"].row(
                    f"transition:{oid}:{action}:{version - 1}", "STATE_TRANSITION", oid, vid, uid, key, None, "RESTORED",
                    ts(created + timedelta(minutes=16)), None, ts(created + timedelta(minutes=15)))
            if pay:
                order_files["commerce_payment"].row(f"pay-{oid}", oid, 1990, 1, "REFUNDED" if status == 6 else "PAID",
                                                    f"sim:pay-{oid}", "PAID", ts(pay - timedelta(seconds=5)))
            order_files["processed_event"].row(AUDIT_GROUP, str(uuid.UUID(int=oid << 8)),
                                               hashlib.sha256(key.encode()).hexdigest(), "commerce.order.created",
                                               oid, 0, "PROCESSED", ts(created + timedelta(seconds=2)))
            order_files["event_projection_version"].row(AUDIT_GROUP, "commerce_order", oid, status,
                                                        ts(created + timedelta(seconds=2)))
            order_files["shop_order_sales"].row(oid, sid, status, status)
            if status in (2, 3):
                sold[sid] = sold.get(sid, 0) + 1
            orders_for_outbox.append((created, oid, uid, vid, sid, status))
        stock = active + rng.randint(0, max(1, voucher_buyers[vid] // 10))
        order_files["commerce_seckill_voucher"].row(vid, stock, ts(begin), ts(end), 1,
                                                    ts(min(end + timedelta(days=1, minutes=rng.randint(1, 600)), NOW)))
    columns = {
        "commerce_order": ["id", "user_id", "voucher_id", "shop_id", "shop_name_snapshot", "voucher_title_snapshot",
                           "voucher_sub_title_snapshot", "voucher_rules_snapshot", "pay_value_snapshot",
                           "actual_value_snapshot", "pay_type", "status", "version", "idempotency_key", "create_time",
                           "pay_time", "use_time", "refund_time", "pay_deadline", "close_reason"],
        "commerce_reservation": ["order_id", "voucher_id", "user_id", "status", "version", "stream_record_id", "created_at"],
        "commerce_admission_ledger": ["order_id", "user_id", "idempotency_key", "voucher_id", "request_fingerprint",
                                      "routing_generation", "physical_shard_count", "status", "accepted_at",
                                      "terminal_at", "created_at"],
        "commerce_order_recovery": ["source_id", "source_type", "order_id", "voucher_id", "user_id", "idempotency_key",
                                    "payload_json", "phase", "terminal_at", "finalized_at", "created_at"],
        "commerce_payment": ["payment_id", "order_id", "amount", "pay_type", "status", "channel_transaction_id",
                             "callback_result", "created_at"],
        "commerce_seckill_voucher": ["voucher_id", "stock", "begin_time", "end_time", "version", "redis_archived_at"],
        "processed_event": ["consumer_group", "event_id", "payload_hash", "event_type", "aggregate_id",
                            "aggregate_version", "status", "processed_at"],
        "event_projection_version": ["consumer_group", "aggregate_type", "aggregate_id", "current_version", "updated_at"],
        "shop_order_sales": ["order_id", "shop_id", "status", "aggregate_version"],
    }
    for name, writer in order_files.items():
        db = "core" if name in ("processed_event", "event_projection_version", "shop_order_sales") else "commerce"
        files[name] = (writer.close(), columns[name], db)

    for row in shop_rows:
        row[10] = sold.get(row[0], 0)
        shop_writer.row(*row)
    files["shop"] = (shop_writer.close(), ["id", "name", "type_id", "owner_user_id", "images", "area", "address", "x", "y",
                                 "avg_price", "sold", "comments", "score", "open_hours", "create_time", "geo_source",
                                 "geo_status"], "core")
    w = Writer(out, "shop_summary_projection")
    for sid in shop_ids:
        w.row(sid, f"店铺{sid}", shop_owner[sid], 0)
    files["shop_summary_projection"] = (w.close(), ["shop_id", "shop_name", "owner_user_id", "source_version"], "commerce")

    # 只保留最近的 Outbox：模拟已发布事件随时间清理后的存量
    w = Writer(out, "commerce_outbox_event")
    orders_for_outbox.sort(reverse=True)
    for created, oid, uid, vid, sid, status in orders_for_outbox:
        if w.rows >= n["outbox"]:
            break
        for offset, (action, version) in enumerate([("created", 0)] + [(a, ACTION_STATE[a]) for a in STATUS_EVENTS[status]]):
            at = created + timedelta(minutes=offset * 10)
            payload = {"eventId": str(uuid.UUID(int=(oid << 8) | version)), "eventType": f"commerce.order.{action}",
                       "schemaVersion": 2, "aggregateType": "commerce_order", "aggregateId": str(oid),
                       "aggregateVersion": version, "producer": "commerce-service", "occurredAt": at.isoformat(),
                       "correlationId": str(oid), "payload": {"orderId": oid, "userId": uid, "voucherId": vid,
                                                              "shopId": sid, "status": 1 if action == "created" else version}}
            w.row(payload["eventId"], payload["eventType"], "commerce_order", oid, version, TOPIC,
                  json.dumps(payload, ensure_ascii=False), "PUBLISHED", 1, 1, ts(at), ts(at + timedelta(seconds=1)), ts(at))
    files["commerce_outbox_event"] = (w.close(), ["event_id", "event_type", "aggregate_type", "aggregate_id",
                                                  "aggregate_version", "topic", "payload_json", "status", "attempts",
                                                  "fencing_token", "next_attempt_at", "published_at", "created_at"],
                                      "commerce")

    # ---------- Core：笔记、点赞、关注 ----------
    w = Writer(out, "note")
    note_ids = list(range(1, n["notes"] + 1))
    note_pick = Zipf(rng, note_ids, 1.0)
    like_counts = {}
    for nid in rng.choices(note_pick.ids, cum_weights=note_pick.cumulative, k=n["likes"]):
        like_counts[nid] = like_counts.get(nid, 0) + 1
    note_time = {}
    for nid in note_ids:
        note_time[nid] = NOW - timedelta(days=rng.uniform(0, 365))
        w.row(nid, shop_pick.one(), user_pick.one(), f"探店笔记{nid}", "note.png", "环境不错，" * 30,
              min(like_counts.get(nid, 0), n["users"]), 0, ts(note_time[nid]))
    files["note"] = (w.close(), ["id", "shop_id", "user_id", "title", "images", "content", "liked", "comments",
                                 "create_time"], "core")
    w = Writer(out, "note_like")
    for nid, count in like_counts.items():
        base = int(note_time[nid].timestamp() * 1000)
        for uid in rng.sample(users, min(count, len(users))):
            w.row(nid, uid, base + rng.randint(1, 30 * 86_400_000))
    files["note_like"] = (w.close(), ["note_id", "user_id", "liked_at"], "core")
    w = Writer(out, "user_follow")
    pairs = set()
    while len(pairs) < n["follows"]:
        follower, target = rng.choice(users), user_pick.one()
        if follower != target:
            pairs.add((follower, target))
    for index, (follower, target) in enumerate(pairs, 1):
        w.row(index, follower, target, ts(NOW - timedelta(days=rng.uniform(0, 365))))
    files["user_follow"] = (w.close(), ["id", "user_id", "follow_user_id", "create_time"], "core")

    # ---------- Commerce：预约资源、时段、预约 ----------
    res_w, slot_w, offer_w, book_w = (Writer(out, name) for name in (
        "commerce_booking_resource", "commerce_booking_slot", "commerce_booking_offer", "commerce_booking"))
    guards, slot_id = set(), 0
    today = NOW.replace(hour=11, minute=0, second=0)  # 本地 19:00
    for rid in range(1, n["resources"] + 1):
        sid = shop_pick.one()
        court = rng.random() < 0.6
        kind, capacity = ("COURT", 1) if court else ("DINING_POOL", 30)
        res_w.row(rid, sid, kind, f"{'场地' if court else '餐次'}{rid}", "Asia/Shanghai", 4 if court else 10, "OPEN", 0)
        offer_w.row(rid, rid, sid, "COURT_SLOT" if court else "PER_PERSON_PACKAGE",
                    "场地预订" if court else "双人套餐", rng.randint(80, 200) * 100 if court else rng.randint(60, 150) * 100)
        for day in range(-120, 30):
            slot_id += 1
            start = today + timedelta(days=day)
            end = start + timedelta(hours=1 if court else 2)
            past = day < 0
            occupied = 0
            count = (1 if rng.random() < (0.8 if past else 0.3) else 0) if court else rng.randint(0, 2 if past else 1)
            for _ in range(count):
                party = rng.randint(1, 4) if court else rng.randint(2, 6)
                units = 1 if court else party
                if occupied + units > capacity:
                    break
                r = rng.random()
                status = ("USED" if r < 0.75 else "CANCELLED" if r < 0.87 else "NO_SHOW" if r < 0.92 else "HOLD_EXPIRED") \
                    if past else "CONFIRMED"
                if status in ("USED", "NO_SHOW", "CONFIRMED"):
                    occupied += units
                uid = user_pick.one()
                guards.add(uid)
                created = start - timedelta(hours=rng.uniform(1, 168))
                book_w.row(str(uuid.UUID(int=rng.getrandbits(128))), uid, sid, rid, slot_id, f"资源{rid}", kind, party,
                           units, status, "USER_CANCELLED" if status == "CANCELLED" else None, ts(start), ts(end),
                           ts(created + timedelta(minutes=10)), 1 if status in ("CONFIRMED", "HOLD_EXPIRED") else 2,
                           hashlib.sha256(f"{slot_id}-{uid}".encode()).hexdigest() if status == "CONFIRMED" else None,
                           '{"pricingMode":"ON_SITE","currency":"CNY","totalCents":null}',
                           ts(created), ts(start + timedelta(minutes=10)) if status == "USED" else None)
            slot_w.row(slot_id, rid, ts(start), ts(end), capacity, occupied, "OPEN", 0)
    files["commerce_booking_resource"] = (res_w.close(), ["id", "shop_id", "kind", "name", "timezone", "max_party_size",
                                                          "status", "version"], "commerce")
    files["commerce_booking_offer"] = (offer_w.close(), ["id", "resource_id", "shop_id", "kind", "title",
                                                         "unit_price_cents"], "commerce")
    files["commerce_booking_slot"] = (slot_w.close(), ["id", "resource_id", "start_at", "end_at", "capacity", "occupied",
                                                       "status", "version"], "commerce")
    files["commerce_booking"] = (book_w.close(), ["id", "user_id", "shop_id", "resource_id", "slot_id", "resource_name",
                                                  "resource_kind", "party_size", "units", "status", "reason_code",
                                                  "start_at", "end_at", "hold_expires_at", "version", "checkin_token_hash",
                                                  "price_snapshot", "created_at", "used_at"], "commerce")
    w = Writer(out, "commerce_booking_user_guard")
    for uid in guards:
        w.row(uid)
    files["commerce_booking_user_guard"] = (w.close(), ["user_id"], "commerce")
    return {"files": files, "shopExponent": round(shop_s, 3), "seckillShopExponent": round(seckill_shop_s, 3),
            "userExponent": round(user_s, 3),
            "hotVoucherBuyers": sorted(voucher_buyers[v] for v in hot)}


LOAD_ORDER = ["user_account", "shop", "note", "note_like", "user_follow", "processed_event", "event_projection_version",
              "shop_order_sales", "shop_summary_projection", "commerce_voucher", "commerce_seckill_voucher",
              "commerce_order", "commerce_reservation", "commerce_admission_ledger", "commerce_order_recovery",
              "commerce_payment", "commerce_outbox_event", "commerce_booking_resource", "commerce_booking_offer",
              "commerce_booking_slot", "commerce_booking", "commerce_booking_user_guard"]


def verify(core: Db, commerce: Db) -> dict:
    def one(db: Db, statement: str):
        return db.sql(statement)[0]
    counts = {table: int(one(core if table in ("user_account", "shop", "note", "note_like", "user_follow",
                                              "processed_event", "event_projection_version", "shop_order_sales")
                             else commerce, f"SELECT COUNT(*) FROM {table}")[0]) for table in LOAD_ORDER}
    per_shop = sorted((int(c) for _, c in commerce.sql("SELECT shop_id, COUNT(*) FROM commerce_order GROUP BY shop_id")),
                      reverse=True)
    shops = counts["shop"]
    per_user = sorted((int(c) for _, c in commerce.sql("SELECT user_id, COUNT(*) FROM commerce_order GROUP BY user_id")),
                      reverse=True)
    total = sum(per_shop)
    statuses = {s: int(c) for s, c in commerce.sql("SELECT status, COUNT(*) FROM commerce_order GROUP BY status")}
    sold_total, sales_total = one(core, "SELECT (SELECT SUM(sold) FROM shop), "
                                        "(SELECT COUNT(*) FROM shop_order_sales WHERE status IN (2,3))")
    bad_slots = one(commerce, """SELECT COUNT(*) FROM commerce_booking_slot s LEFT JOIN
        (SELECT slot_id, SUM(units) u FROM commerce_booking WHERE status IN ('HELD','CONFIRMED','USED','NO_SHOW')
         GROUP BY slot_id) b ON b.slot_id=s.id WHERE s.occupied<>COALESCE(b.u,0)""")[0]
    sizes = {name: float(mb) for name, mb in core.sql(
        "SELECT table_name, ROUND((data_length+index_length)/1048576) FROM information_schema.tables "
        "WHERE table_schema='citycopilot'") + commerce.sql(
        "SELECT table_name, ROUND((data_length+index_length)/1048576) FROM information_schema.tables "
        "WHERE table_schema='citycopilot_commerce'")}
    return {
        "rows": counts,
        "top1PctShopsOrderShare": round(sum(per_shop[:max(1, shops // 100)]) / total, 3),
        "top10PctUsersOrderShare": round(sum(per_user[:max(1, counts["user_account"] // 10)]) / total, 3),
        "orderStatusShare": {s: round(c / total, 3) for s, c in sorted(statuses.items())},
        "soldMatchesProjection": sold_total == sales_total,
        "slotsWithWrongOccupancy": int(bad_slots),
        "totalSizeMb": sum(sizes.values()),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project", default="citycopilot-bigdata")
    parser.add_argument("--scale", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=20261015)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT / "tools/loadtest/results/bigdata-seed.json")
    args = parser.parse_args()
    env = env_file()
    core = Db(args.project, "mysql", env["CITYCOPILOT_MYSQL_ROOT_PASSWORD"], "citycopilot")
    commerce = Db(args.project, "commerce-mysql", env["CITYCOPILOT_COMMERCE_MYSQL_ROOT_PASSWORD"], "citycopilot_commerce")
    report = {"scale": args.scale, "seed": args.seed, "generatedAt": NOW.isoformat()}
    if not args.verify_only:
        if int(core.sql("SELECT COUNT(*) FROM user_account")[0][0]) or int(commerce.sql("SELECT COUNT(*) FROM commerce_order")[0][0]):
            raise SystemExit("Target databases already contain data; recreate the bigdata project volumes first")
        work = ROOT / "tools/loadtest/results/bigdata-tsv"
        work.mkdir(parents=True, exist_ok=True)
        started = time.perf_counter()
        generated = generate(args, work)
        report["generateSeconds"] = round(time.perf_counter() - started, 1)
        shop_types = [line for line in (ROOT / "deploy/mysql/seed/core.sql").read_text(encoding="utf-8").splitlines()
                      if line.startswith("INSERT INTO `shop_type`")]
        core.sql("\n".join(shop_types))
        load_seconds = {}
        for table in LOAD_ORDER:
            path, columns, target = generated["files"][table]
            load_seconds[table] = round((core if target == "core" else commerce).load(table, columns, path), 1)
            path.unlink()
            print(f"loaded {table} in {load_seconds[table]}s", flush=True)
        work.rmdir()
        # 号段游标推进到导入数据之后，新订单和新券的 ID 不会撞上历史数据。
        commerce.sql("""INSERT INTO commerce_id_segment(namespace,next_value)
            SELECT 'order', MAX(id)+1 FROM commerce_order UNION ALL SELECT 'voucher', MAX(id)+1 FROM commerce_voucher
            ON DUPLICATE KEY UPDATE next_value=GREATEST(next_value, VALUES(next_value));""")
        for db in (core, commerce):
            tables = [row[0] for row in db.sql(f"SELECT table_name FROM information_schema.tables WHERE table_schema='{db.schema}'")]
            db.sql("ANALYZE TABLE " + ",".join(tables) + ";")
        report.update({"loadSeconds": load_seconds, "shopExponent": generated["shopExponent"],
                       "seckillShopExponent": generated["seckillShopExponent"],
                       "userExponent": generated["userExponent"], "hotVoucherBuyers": generated["hotVoucherBuyers"]})
    report["verification"] = verify(core, commerce)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["verification"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
