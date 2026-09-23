#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
限流行为验证探针 —— 与 load_test.py 互补。

分工：
  load_test.py        回答「接口有多快」（吞吐、分位数、缓存收益）；
  本脚本              回答「防护有没有生效」（同一主体超阈值时是否被拦住）。

为什么不能只看配置文件：
  把 `http-per-minute: 100` 写进 YAML、把 `RateLimiter` 写成 @Component、
  再给它配上单测，这三件事**都不等于限流生效** —— 只要没有任何
  Filter / Interceptor 在请求链路上调用 `tryAcquireHttp`，限流器就只是一个
  「能被监控端点读出来的配置对象」。本脚本用真实请求把这个差距暴露出来。

判据：
  1 分钟窗口内对**同一个 VIN** 连打 N 次（N > 阈值）：
    - 第 100 次之后开始出现业务码 4001 → 限流已接入；
    - N 次全部成功                → 限流**未接入**该请求链路。

用法:
    python perf/rate_limit_probe.py --base-url http://127.0.0.1:8080 \
        --vin LSVAA1234567890 --count 130 --username admin --password 'xxx'
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# 复用压测脚本的 HTTP 与登录实现，避免两份会各自漂移的副本
sys.path.insert(0, str(Path(__file__).resolve().parent))
from load_test import http_get, login  # noqa: E402

# 平台限流被拒时返回的业务码（ErrorCode.RATE_LIMITED）
CODE_RATE_LIMITED = "4001"

# 鉴权类业务码：命中这些说明请求根本没进入业务层，此时「没有限流」不能作为结论
AUTH_FAILURE_CODES = {"2001", "2002", "2003"}


def main() -> int:
    parser = argparse.ArgumentParser(description="DSSAD 云平台限流行为验证")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--vin", required=True, help="用于打限流的车辆 VIN（限流键 = VIN）")
    parser.add_argument("--path", default="/api/v1/vehicles/{vin}",
                        help="打哪个接口，URL 中可用 {vin} 占位")
    parser.add_argument("--count", type=int, default=130,
                        help="连续请求次数，应大于配置的 http-per-minute")
    parser.add_argument("--token", default=None)
    parser.add_argument("--username", default=None)
    parser.add_argument("--password", default=None)
    parser.add_argument("--timeout", type=float, default=10.0)
    args = parser.parse_args()

    token = args.token
    if not token and args.username and args.password:
        token, reason = login(args.base_url, args.username, args.password)
        if not token:
            print(f"[登录] 失败：{reason}")
            return 2
        print(f"[登录] 成功，令牌长度 {len(token)}")

    path = args.path.replace("{vin}", args.vin)
    url = args.base_url + path
    print("=" * 88)
    print(f"限流探针  target={url}  次数={args.count}  鉴权={'已登录' if token else '未登录'}")
    print("=" * 88)

    ok = 0
    limited = 0
    auth_failed = 0
    other: dict[str, int] = {}
    first_limited_at = None

    for index in range(1, args.count + 1):
        status, elapsed, code, error = http_get(url, token, args.timeout)
        if code == CODE_RATE_LIMITED or status == 429:
            limited += 1
            if first_limited_at is None:
                first_limited_at = index
        elif error is None and 200 <= status < 300 and code in (None, "0000"):
            ok += 1
        else:
            if code in AUTH_FAILURE_CODES or status in (401, 403):
                auth_failed += 1
            key = error or f"HTTP {status} / 业务码 {code}"
            other[key] = other.get(key, 0) + 1

    print(f"\n成功 {ok} 次，被拒（{CODE_RATE_LIMITED}）{limited} 次，"
          f"鉴权失败 {auth_failed} 次，其他失败 {sum(other.values()) - auth_failed} 次")
    for key, value in sorted(other.items(), key=lambda kv: -kv[1])[:5]:
        print(f"  失败明细：{key} × {value}")
    if first_limited_at is not None:
        print(f"首次被拒出现在第 {first_limited_at} 次请求")

    print("\n[判定]")
    if limited > 0:
        print(f"  限流已生效：共被拒 {limited} 次，首次出现在第 {first_limited_at} 次请求。")
        return 0
    if auth_failed == args.count:
        print("  全部请求因鉴权失败被提前拦截，无法判断限流 —— 请先解决令牌问题再复测。")
        return 1
    print(f"  ⚠️ 未观察到任何限流：同一 VIN 连续 {args.count} 次请求，无一次返回 {CODE_RATE_LIMITED}。")
    if ok == args.count:
        print(f"     全部 {ok} 次均为业务码 0000，即 {args.count} 次请求都完整走完了业务处理。")
    else:
        print("     失败部分是业务层错误（请求已进入业务处理），同样说明其间没有任何拦截。")
    print("     限流判定本应**先于**业务处理执行，故结论明确：")
    print("     请求链路上不存在调用 RateLimiter.tryAcquireHttp() 的 Filter/Interceptor，")
    print("     `dssad.rate-limit.*` 目前只是一份「可被监控端点读出来的配置」。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
