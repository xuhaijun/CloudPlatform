#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
限流行为验证探针 —— 与 load_test.py 互补，可作**发布门禁**使用。

分工：
  load_test.py        回答「接口有多快」（吞吐、分位数、缓存收益）；
  本脚本              回答「防护有没有生效」（同一主体超阈值时是否被拦住）。

为什么不能只看配置文件：
  把 `http-per-minute: 100` 写进 YAML、把 `RateLimiter` 写成 @Component、
  再给它配上单测，这三件事**都不等于限流生效** —— 只要没有任何
  Filter / Interceptor 在请求链路上调用 `tryAcquireHttp`，限流器就只是一个
  「能被监控端点读出来的配置对象」。F-01（200 用例全绿仍潜伏的 P0）正是这样漏掉的，
  本脚本用真实请求把这个差距暴露出来，因此**每次发布前都应跑一遍**（T-02 ③ 门禁）。

两种模式：
  enterprise（默认）  打企业侧接口 /enterprise/api/v1/**（限流已接线的路径）。
                      请求按接口文档 6.3.1 签名：X-Sign = HMAC-SHA256(secret, ts.nonce.body)，
                      secret = SHA256(enterpriseId + salt)；目标端点用只读的 video/history，
                      避免门禁探针本身产生业务副作用。
  admin               打平台管理接口 /api/v1/**（token 鉴权 GET）。
                      ⚠️ 该链路**按设计不限流**（上游 9.1 的「·车」配额约束的是企业侧
                      按车辆发起的调用；管理端是操作员界面），此模式仅用于对照观察，
                      预期结果就是「无限流」，不能作为门禁判据。

判据（与限流算法无关的黑盒断言）：
  1 分钟窗口内对**同一个 VIN** 连打 N 次（N > 阈值，默认 130）：
    - 第 100 次之后开始出现业务码 4001 → 限流已接入；
    - N 次全部成功                → 限流**未接入**该请求链路（门禁不通过）。

用法:
    # 发布门禁（企业侧，已接线路径）
    python perf/rate_limit_probe.py --base-url http://127.0.0.1:8080 \
        --vin LSVAA1234567890 --enterprise-id ENT001 --count 130

    # 对照观察（管理端，预期无限流）
    python perf/rate_limit_probe.py --mode admin --base-url http://127.0.0.1:8080 \
        --vin LSVAA1234567890 --count 130 --username admin --password 'xxx'
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

# 复用压测脚本的 HTTP 与登录实现，避免两份会各自漂移的副本
sys.path.insert(0, str(Path(__file__).resolve().parent))
from load_test import http_get, login  # noqa: E402

# 平台限流被拒时返回的业务码（ErrorCode.RATE_LIMITED）
CODE_RATE_LIMITED = "4001"

# 鉴权类业务码：命中这些说明请求根本没进入业务层，此时「没有限流」不能作为结论
AUTH_FAILURE_CODES = {"2001", "2002", "2003"}


def _secret(enterprise_id: str, salt: str) -> str:
    """派生签名密钥：SHA256(enterpriseId + salt) 小写十六进制（与 RegulatorySigner 一致）。"""
    return hashlib.sha256((enterprise_id + salt).encode("utf-8")).hexdigest()


def http_post_signed(url: str, body: str, enterprise_id: str, secret: str,
                     timeout: float) -> tuple[int, float, str | None, str | None]:
    """发送一条带签名的企业侧 POST 请求。

    返回 (HTTP 状态码, 耗时秒, 业务码, 错误信息)。与 load_test.http_get 的返回约定一致。
    每次调用都生成新的 timestamp/nonce —— 复用 nonce 会被签名过滤器的防重放直接拦下，
    那样测的是「防重放」而不是「限流」。
    """
    timestamp = str(int(time.time() * 1000))
    nonce = uuid.uuid4().hex
    sign = hmac.new(secret.encode("utf-8"),
                    f"{timestamp}.{nonce}.{body}".encode("utf-8"),
                    hashlib.sha256).hexdigest()

    request = urllib.request.Request(url, data=body.encode("utf-8"), method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/json")
    request.add_header("X-Enterprise-Id", enterprise_id)
    request.add_header("X-Timestamp", timestamp)
    request.add_header("X-Nonce", nonce)
    request.add_header("X-Sign", sign)

    # 探针通常打本机/内网回环地址；部分开发机注入了全局 http_proxy，
    # 会把 127.0.0.1 的请求也转发出去并得到 502 —— 因此显式绕过一切代理。
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    start = time.perf_counter()
    try:
        with opener.open(request, timeout=timeout) as response:
            payload = response.read().decode("utf-8", "replace")
            elapsed = time.perf_counter() - start
            try:
                code = json.loads(payload).get("code")
            except (ValueError, AttributeError):
                code = None
            return response.status, elapsed, code, None
    except urllib.error.HTTPError as e:
        elapsed = time.perf_counter() - start
        try:
            code = json.loads(e.read().decode("utf-8", "replace")).get("code")
        except (ValueError, AttributeError):
            code = None
        return e.code, elapsed, code, str(e.reason)
    except Exception as e:  # 连接被拒 / 超时等
        return 0, time.perf_counter() - start, None, str(e)


def run_probe(args) -> tuple[int, int, int, dict[str, int], int | None]:
    """执行连打循环，返回 (ok, limited, auth_failed, other, first_limited_at)。"""
    enterprise_mode = args.mode == "enterprise"
    if enterprise_mode:
        path = args.path.replace("{vin}", args.vin)
        url = args.base_url + path
        secret = _secret(args.enterprise_id, args.secret_salt)
        # 只读查询（区间取 1970 年附近，库中无数据也能最快返回），
        # 门禁探针不该给自己制造业务副作用
        body = (f'{{"vin":"{args.vin}","msgId":"{uuid.uuid4()}","cameraDirection":"1",'
                f'"startTime":"2000-01-01 00:00:00","endTime":"2000-01-01 00:01:00"}}')
        print(f"限流探针（企业侧/已接线路径）  target={url}  次数={args.count}")
    else:
        path = args.path.replace("{vin}", args.vin)
        url = args.base_url + path
        print(f"限流探针（管理端对照，预期不限流）  target={url}  次数={args.count}")

    ok = 0
    limited = 0
    auth_failed = 0
    other: dict[str, int] = {}
    first_limited_at = None

    for index in range(1, args.count + 1):
        if enterprise_mode:
            status, _elapsed, code, error = http_post_signed(
                url, body, args.enterprise_id, secret, args.timeout)
        else:
            status, _elapsed, code, error = http_get(url, args.token, args.timeout)

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

    return ok, limited, auth_failed, other, first_limited_at


def main() -> int:
    parser = argparse.ArgumentParser(description="DSSAD 云平台限流行为验证（发布门禁）")
    parser.add_argument("--mode", choices=("enterprise", "admin"), default="enterprise",
                        help="enterprise=企业侧已接线路径（默认，门禁用）；admin=管理端对照")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--vin", required=True, help="用于打限流的车辆 VIN（限流键 = VIN）")
    parser.add_argument("--path", default=None,
                        help="打哪个接口，URL 中可用 {vin} 占位；"
                             "enterprise 模式默认 /enterprise/api/v1/video/history，"
                             "admin 模式默认 /api/v1/vehicles/{vin}")
    parser.add_argument("--enterprise-id", default="DSSAD-LOCAL-0001",
                        help="enterprise 模式的企业 ID（须为平台准入企业）")
    parser.add_argument("--secret-salt", default="CMAT",
                        help="签名密钥派生盐，须与 dssad.regulatory.secret-salt 一致")
    parser.add_argument("--count", type=int, default=130,
                        help="连续请求次数，应大于配置的 http-per-minute")
    parser.add_argument("--token", default=None, help="admin 模式的访问令牌")
    parser.add_argument("--username", default=None, help="admin 模式登录用户名")
    parser.add_argument("--password", default=None, help="admin 模式登录密码")
    parser.add_argument("--timeout", type=float, default=10.0)
    args = parser.parse_args()

    if args.path is None:
        args.path = ("/enterprise/api/v1/video/history" if args.mode == "enterprise"
                     else "/api/v1/vehicles/{vin}")

    token = args.token
    if args.mode == "admin" and not token and args.username and args.password:
        token, reason = login(args.base_url, args.username, args.password)
        if not token:
            print(f"[登录] 失败：{reason}")
            return 2
        args.token = token
        print(f"[登录] 成功，令牌长度 {len(token)}")

    print("=" * 88)
    ok, limited, auth_failed, other, first_limited_at = run_probe(args)
    print(f"\n成功 {ok} 次，被拒（{CODE_RATE_LIMITED}）{limited} 次，"
          f"鉴权失败 {auth_failed} 次，其他失败 {sum(other.values()) - auth_failed} 次")
    for key, value in sorted(other.items(), key=lambda kv: -kv[1])[:5]:
        print(f"  失败明细：{key} × {value}")
    if first_limited_at is not None:
        print(f"首次被拒出现在第 {first_limited_at} 次请求")

    print("\n[判定]")
    if limited > 0:
        print(f"  ✅ 限流已生效：共被拒 {limited} 次，首次出现在第 {first_limited_at} 次请求。")
        return 0
    if auth_failed == args.count:
        print("  全部请求因鉴权失败被提前拦截，无法判断限流 —— 请先解决签名/令牌问题再复测。")
        return 1
    print(f"  ⚠️ 未观察到任何限流：同一 VIN 连续 {args.count} 次请求，无一次返回 {CODE_RATE_LIMITED}。")
    if args.mode == "admin":
        print("     管理端链路按设计不限流（上游 9.1 的「·车」配额针对企业侧按车辆调用），")
        print("     此结果符合预期，不能作为门禁判据；请用 --mode enterprise 复测。")
        return 0
    print("     限流判定本应**先于**业务处理执行，故结论明确：")
    print("     企业侧请求链路上不存在（或未生效）调用 RateLimiter.tryAcquireHttp() 的过滤器，")
    print("     `dssad.rate-limit.*` 沦为「可被监控端点读出来的配置」—— 门禁不通过，禁止发布。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
