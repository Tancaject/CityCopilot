"""一次性清理修复前写入、永久保留的秒杀 Redis 幂等键。

修复后新写入的全局幂等键带 1 天过期，券内幂等键在活动归档时删除；修复前的存量需要本脚本处理一次：
- 全局幂等键：补 1 天过期（EXPIRE ... NX，只给没有过期时间的键设置，不缩短已有的过期时间）。
  过期后同一幂等键重试由 MySQL 准入账本重放，身份不变。
- 券内幂等键：所属活动已归档（commerce_seckill_voucher.redis_archived_at 非空）的直接删除；
  未归档活动的键留给归档时删除，归档前 Lua 准入仍要读它们。
脚本可重复执行。默认只统计，加 --apply 才写 Redis。

用法（仓库根目录）：
    python tools/backfill_redis_idempotency.py                # 日常 compose 项目，只统计
    python tools/backfill_redis_idempotency.py --apply
    python tools/backfill_redis_idempotency.py --project citycopilot-bigdata --apply
"""
import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GLOBAL_PATTERN = "commerce:{commerce-idempotency:*"
VOUCHER_PATTERN = "commerce:{commerce:*}:seckill:idempotency:*"
VOUCHER_MARKER = ":seckill:idempotency:"
TTL_SECONDS = 24 * 3600


def compose(project: str, service: str, command: str, payload: bytes = b"") -> bytes:
    result = subprocess.run(
        ["docker", "compose", "-p", project, "-f", str(ROOT / "compose.yaml"), "exec", "-T", service,
         "sh", "-ec", command], input=payload, capture_output=True, cwd=ROOT)
    if result.returncode:
        raise RuntimeError(f"{service} command failed: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout


def redis(project: str, arguments: str, payload: bytes = b"") -> bytes:
    return compose(project, "commerce-redis",
                   'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli ' + arguments, payload)


def scan(project: str, pattern: str) -> list[str]:
    return [key for key in redis(project, f"--scan --count 1000 --pattern '{pattern}'")
            .decode("utf-8").splitlines() if key]


def pipe(project: str, commands: list[tuple[str, ...]]) -> str:
    """用 redis-cli --pipe 批量执行命令，按 RESP 协议编码，键中可以含空格等字符。"""
    payload = b"".join(
        f"*{len(parts)}\r\n".encode() + b"".join(
            f"${len(part.encode())}\r\n".encode() + part.encode() + b"\r\n" for part in parts)
        for parts in commands)
    output = redis(project, "--pipe", payload).decode("utf-8", errors="replace")
    if "errors: 0" not in output:
        raise RuntimeError("Redis pipeline reported errors: " + output[-300:])
    return output.strip().splitlines()[-1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--project", default="citycopilot")
    parser.add_argument("--commerce-database", default="citycopilot_commerce")
    parser.add_argument("--apply", action="store_true", help="写入 Redis；不加时只统计")
    args = parser.parse_args()

    archived = {int(line) for line in compose(
        args.project, "commerce-mysql",
        f'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B {args.commerce_database} '
        '-e "SELECT voucher_id FROM commerce_seckill_voucher WHERE redis_archived_at IS NOT NULL"')
        .decode().split() if line}

    global_keys = scan(args.project, GLOBAL_PATTERN)
    voucher_keys = scan(args.project, VOUCHER_PATTERN)
    stale = [key for key in voucher_keys
             if int(key.split(VOUCHER_MARKER, 1)[1].split(":", 1)[0]) in archived]
    print(f"global idempotency keys: {len(global_keys)} (keys without TTL get {TTL_SECONDS}s)")
    print(f"voucher idempotency keys: {len(voucher_keys)}, of archived campaigns: {len(stale)} "
          f"({len(archived)} archived campaigns)")
    if not args.apply:
        print("dry run; add --apply to write Redis")
        return
    if global_keys:
        print("expire:", pipe(args.project, [("EXPIRE", key, str(TTL_SECONDS), "NX") for key in global_keys]))
    if stale:
        print("unlink:", pipe(args.project, [("UNLINK", key) for key in stale]))


if __name__ == "__main__":
    main()
