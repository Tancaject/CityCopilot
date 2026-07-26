#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CityCopilot 秒杀压测 · 数据准备 / 结果校验脚本

用法：
    python seed_loadtest.py            # 准备数据：造秒杀券 + 灌 N 个登录 token + 导出 tokens.csv
    python seed_loadtest.py --verify   # 压测跑完后校验：成单数 / 零超卖 / 一人一单
    python seed_loadtest.py --stock 100 --tokens 5000   # 临时覆盖库存和 token 数量

两类测试：
    · 吞吐(QPS)测试：让 库存 >= token 数（默认 5000/5000），大多数请求成功，看接口 QPS / P99。
    · 零超卖测试：  --stock 100 --tokens 5000，预期最终 DB 成单恰好 = 100，多出来就是超卖 bug。
"""

import argparse
import csv
import os
import sys
import time
import uuid

# Windows 控制台默认 GBK，强制 UTF-8 以正常输出中文和符号
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

try:
    import redis
    import pymysql
except ImportError:
    sys.exit("缺少依赖，请先运行：python -m pip install redis pymysql")

# ============================================================
# CONFIG —— 如果你启动后端时改过 DB 密码 / Redis 地址，改这里！
# 默认值取自 backend/core/.../application.yaml
# ============================================================
DB_HOST = os.getenv("CITYCOPILOT_DB_HOST", "127.0.0.1")
DB_PORT = int(os.getenv("CITYCOPILOT_DB_PORT", "3306"))
DB_USER = os.getenv("CITYCOPILOT_DB_USERNAME", "root")
DB_PASS = os.getenv("CITYCOPILOT_DB_PASSWORD", "")
DB_NAME = os.getenv("CITYCOPILOT_DB_NAME", "citycopilot")

REDIS_HOST = os.getenv("CITYCOPILOT_REDIS_HOST", "127.0.0.1")
REDIS_PORT = int(os.getenv("CITYCOPILOT_REDIS_PORT", "6379"))
REDIS_PASS = os.getenv("CITYCOPILOT_REDIS_PASSWORD", "") or None
REDIS_DB = int(os.getenv("CITYCOPILOT_REDIS_DATABASE", "0"))

VOUCHER_ID = 100          # 专门给压测用的秒杀券 id（避开已有数据）
SHOP_ID = 1
DEFAULT_STOCK = 5000
DEFAULT_TOKENS = 5000
TOKEN_TTL_SECONDS = 7200  # token 有效期，够你跑测试即可
START_USER_ID = 100000    # 测试用户 id 从这里开始，避开真实用户

OUT_CSV = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tokens.csv")

LOGIN_KEY = "login:token:"
STOCK_KEY = "coupon:stock:"
ORDER_KEY = "coupon:buyer:"
# ============================================================


def db_conn():
    return pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                           password=DB_PASS, database=DB_NAME, charset="utf8mb4",
                           autocommit=True)


def redis_conn():
    return redis.Redis(host=REDIS_HOST, port=REDIS_PORT, password=REDIS_PASS,
                       db=REDIS_DB, decode_responses=True)


def seed(stock, num_tokens):
    # [0] 最先清空异步下单队列，避免上一轮残留消息被 worker 消费成"幽灵订单"污染本轮
    print(f"[0/4] 连接 Redis {REDIS_HOST}:{REDIS_PORT} db{REDIS_DB}，清理消息队列 ...")
    r = redis_conn()
    r.ping()
    try:
        leftover = r.xlen("stream.coupon-orders")
        if leftover:
            r.xtrim("stream.coupon-orders", maxlen=0, approximate=False)
            print(f"      清空 stream.coupon-orders 残留消息 {leftover} 条，等 worker 落定 ...")
            time.sleep(3)  # 等异步 worker 处理完手上 in-flight 的消息后再清 DB
    except Exception as e:
        print(f"      (stream.coupon-orders 清理跳过: {e})")

    print(f"[1/4] 连接 MySQL {DB_HOST}:{DB_PORT}/{DB_NAME} ...")
    conn = db_conn()
    cur = conn.cursor()

    # 造一张秒杀券（type=1 秒杀, status=1 上架），时间窗设为 过去~未来 保证 active
    cur.execute("""
        INSERT INTO coupon (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
        VALUES (%s, %s, '压测秒杀券', '仅供压测', '压测专用', 1, 100, 1, 1)
        ON DUPLICATE KEY UPDATE type=1, status=1
    """, (VOUCHER_ID, SHOP_ID))
    cur.execute("""
        INSERT INTO seckill_coupon (voucher_id, stock, begin_time, end_time)
        VALUES (%s, %s, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY))
        ON DUPLICATE KEY UPDATE stock=%s,
            begin_time=DATE_SUB(NOW(), INTERVAL 1 DAY),
            end_time=DATE_ADD(NOW(), INTERVAL 30 DAY)
    """, (VOUCHER_ID, stock, stock))
    # 清掉上一轮的订单，保证可重复测 + 一人一单判断干净
    cur.execute("DELETE FROM coupon_order WHERE voucher_id=%s", (VOUCHER_ID,))
    print(f"      秒杀券 id={VOUCHER_ID} 已就绪，DB 库存={stock}，历史订单已清空")

    print(f"[2/4] 重置 Redis 库存与下单集合 ...")
    # 重置 Redis 库存 + 下单去重集合
    r.delete(STOCK_KEY + str(VOUCHER_ID))
    r.delete(ORDER_KEY + str(VOUCHER_ID))
    r.set(STOCK_KEY + str(VOUCHER_ID), stock)
    print(f"      Redis 库存重置为 {stock}，下单集合已清空")

    print(f"[3/4] 灌入 {num_tokens} 个登录 token（每个对应一个不同用户）...")
    tokens = []
    pipe = r.pipeline(transaction=False)
    for i in range(num_tokens):
        uid = START_USER_ID + i
        tok = uuid.uuid4().hex
        tokens.append(tok)
        key = LOGIN_KEY + tok
        pipe.hset(key, mapping={"id": str(uid), "nickName": f"lt_{uid}", "icon": ""})
        pipe.expire(key, TOKEN_TTL_SECONDS)
        if i % 1000 == 999:
            pipe.execute()
            pipe = r.pipeline(transaction=False)
    pipe.execute()

    print(f"[4/4] 导出 token 列表 -> {OUT_CSV}")
    with open(OUT_CSV, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        for tok in tokens:
            w.writerow([tok])

    cur.close()
    conn.close()
    print("\n✅ 准备完成。")
    print(f"   - 秒杀券 id  : {VOUCHER_ID}")
    print(f"   - 库存        : {stock}")
    print(f"   - token 数    : {num_tokens}")
    print(f"   - tokens.csv  : {OUT_CSV}")
    print("   现在去 JMeter 打开 CityCopilot-Seckill.jmx，点绿色三角开始压测。")


def verify(stock, num_tokens):
    print("校验中（如果成单数偏少，可能是异步 worker 还在落库，等几秒再跑一次 --verify）...\n")
    r = redis_conn()
    conn = db_conn()
    cur = conn.cursor()

    cur.execute("SELECT COUNT(*) FROM coupon_order WHERE voucher_id=%s", (VOUCHER_ID,))
    total_orders = cur.fetchone()[0]
    cur.execute("SELECT COUNT(DISTINCT user_id) FROM coupon_order WHERE voucher_id=%s", (VOUCHER_ID,))
    distinct_users = cur.fetchone()[0]
    cur.execute("SELECT stock FROM seckill_coupon WHERE voucher_id=%s", (VOUCHER_ID,))
    db_stock = cur.fetchone()[0]

    redis_stock = r.get(STOCK_KEY + str(VOUCHER_ID))
    order_set = r.scard(ORDER_KEY + str(VOUCHER_ID))
    try:
        stream_len = r.xlen("stream.coupon-orders")
    except Exception:
        stream_len = "?"

    expected_success = min(stock, num_tokens)

    print(f"  预期成单(min(库存,用户)) : {expected_success}")
    print(f"  DB 实际成单数            : {total_orders}")
    print(f"  DB 去重用户数            : {distinct_users}")
    print(f"  DB 剩余库存              : {db_stock}")
    print(f"  Redis 剩余库存           : {redis_stock}")
    print(f"  Redis 下单集合大小       : {order_set}")
    print(f"  stream.coupon-orders 长度: {stream_len}")
    print()

    ok = True
    if total_orders != distinct_users:
        print("  ❌ 一人一单被破坏：成单数 != 去重用户数")
        ok = False
    else:
        print("  ✅ 一人一单：每个用户最多一单")
    if total_orders > stock:
        print(f"  ❌ 超卖！成单 {total_orders} > 库存 {stock}")
        ok = False
    else:
        print(f"  ✅ 零超卖：成单 {total_orders} <= 库存 {stock}")
    if total_orders < expected_success:
        print(f"  ⏳ 成单数({total_orders}) < 预期({expected_success})：worker 可能还在落库，过几秒再 --verify")
    print("\n" + ("✅ 校验通过，可以把这组数字写进简历。" if ok else "⚠️ 有异常，先别写数字，排查上面 ❌ 项。"))

    cur.close()
    conn.close()


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--verify", action="store_true", help="压测后校验结果")
    p.add_argument("--stock", type=int, default=DEFAULT_STOCK, help="库存（默认5000；测零超卖设小，如100）")
    p.add_argument("--tokens", type=int, default=DEFAULT_TOKENS, help="测试用户/请求数（默认5000）")
    args = p.parse_args()
    try:
        if args.verify:
            verify(args.stock, args.tokens)
        else:
            seed(args.stock, args.tokens)
    except pymysql.err.OperationalError as e:
        sys.exit(f"\n❌ MySQL 连接/执行失败：{e}\n   检查脚本顶部 CONFIG 里的 DB_HOST/DB_USER/DB_PASS 是否和你启动后端时一致。")
    except redis.exceptions.RedisError as e:
        sys.exit(f"\n❌ Redis 连接失败：{e}\n   检查脚本顶部 CONFIG 里的 REDIS_HOST/REDIS_PORT/REDIS_PASS。")
