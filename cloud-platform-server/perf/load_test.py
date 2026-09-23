#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
车路通 DSSAD 云平台 —— 只读接口压测脚本（零第三方依赖）。

为什么自己写而不是上 JMeter / k6：
  本平台的性能瓶颈是「**N 个前端 × 每 10 秒 × 4 个统计接口**」这种**低并发、高重复**的
  读场景，而不是秒杀式的高 QPS 写入。这种场景用标准库的线程池 + urllib 就能准确复现，
  省掉一个工具的安装与学习成本，也让压测脚本能跟代码一起进仓库、一起评审。

它验证三件事：
  1. **缓存收益**：同一接口连打两轮，第二轮应当明显更快（L1 命中）。
     若两轮耗时一样 —— 说明缓存没生效（缓存故障是静默的，必须靠压测暴露）。
  2. **限流行为**：单 VIN 的 HTTP 接口应当开始返回 100/429 类的限流响应，
     且**只影响这一个 VIN**，其他 VIN 不受牵连。
  3. **分位数而非均值**：均值会被少数极快请求拉平，掩盖长尾。P95/P99 才是用户体验。

用法:
    # 1) 先起服务（本地 profile，带模拟数据来源）
    mvn spring-boot:run
    # 2) 跑压测
    python perf/load_test.py --base-url http://127.0.0.1:8080 --concurrency 13 --rounds 6

注意：脚本只打 GET 查询接口，不做任何写操作，可以安全地对预发环境执行。
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Callable

# 平台查询侧的热点接口（与前端大屏 / 列表页一一对应）
HOT_ENDPOINTS: list[tuple[str, str]] = [
    ("总览大屏", "/api/v1/dashboard/overview"),
    ("事故趋势", "/api/v1/dashboard/trend?days=7"),
    ("故障分类分布", "/api/v1/dashboard/fault-categories?days=7"),
    ("车辆列表", "/api/v1/vehicles?page=1&size=20"),
    ("链路监控", "/api/v1/monitor/mqtt"),
    ("缓存与限流", "/api/v1/monitor/cache"),
]

# 需要登录令牌的接口（平台的 AdminTokenInterceptor 只读 X-Token 头）。
# ⚠️ 不要写成 "Authorization: Bearer <token>"：平台不认 Bearer 前缀，会一律返回业务码 2001。
# 而「鉴权被拒」的路径比正常查询更快 —— 压测结果会呈现「QPS 极高、耗时极低」，
# 与「性能优秀」的表象完全一致，是最容易被误读的一类压测假象。
TOKEN_HEADER = "X-Token"


@dataclass
class Sample:
    """单次请求的观测值。"""

    endpoint: str
    elapsed_ms: float
    status: int
    # 平台在 HTTP 200 的响应体里用业务码表达失败（如 2001 未授权），
    # 只统计 HTTP 状态码会把「鉴权失败」误判成「性能极好」—— 因为鉴权失败更快。
    code: str | None = None
    error: str | None = None

    @property
    def ok(self) -> bool:
        return self.error is None and 200 <= self.status < 300 and self.code in (None, "0000")

    @property
    def reason(self) -> str:
        if self.error:
            return self.error
        if not 200 <= self.status < 300:
            return f"HTTP {self.status}"
        return f"业务码 {self.code}"


@dataclass
class Report:
    """一个接口的统计结果。"""

    endpoint: str
    samples: list[Sample] = field(default_factory=list)

    def add(self, sample: Sample) -> None:
        self.samples.append(sample)

    @property
    def ok_samples(self) -> list[Sample]:
        return [s for s in self.samples if s.ok]

    def percentile(self, pct: float) -> float:
        values = sorted(s.elapsed_ms for s in self.ok_samples)
        if not values:
            return float("nan")
        # 最近秩法：比线性插值更保守，小样本下不会被单点平滑掉
        index = max(0, min(len(values) - 1, int(round(pct / 100 * len(values) + 0.5)) - 1))
        return values[index]

    def render(self) -> str:
        total = len(self.samples)
        ok = len(self.ok_samples)
        if total == 0:
            return f"  {self.endpoint:<12} 无样本"
        failure = total - ok
        if ok == 0:
            reasons: dict[str, int] = {}
            for s in self.samples:
                reasons[s.reason] = reasons.get(s.reason, 0) + 1
            detail = "，".join(f"{k} × {v}" for k, v in sorted(reasons.items(), key=lambda kv: -kv[1])[:3])
            return f"  {self.endpoint:<12} 全部失败 ({failure}/{total})  原因: {detail}"
        return (
            f"  {self.endpoint:<12} "
            f"样本 {total:>4}  失败 {failure:>3}  "
            f"QPS {self.qps:>7.1f}  "
            f"均值 {statistics.fmean(s.elapsed_ms for s in self.ok_samples):>7.1f}ms  "
            f"P50 {self.percentile(50):>7.1f}ms  "
            f"P95 {self.percentile(95):>7.1f}ms  "
            f"P99 {self.percentile(99):>7.1f}ms"
        )

    qps: float = 0.0


def http_get(url: str, token: str | None, timeout: float) -> tuple[int, float, str | None, str | None]:
    """发起一次 GET，返回 (HTTP 状态码, 耗时毫秒, 业务码, 错误信息)。"""
    request = urllib.request.Request(url, method="GET")
    request.add_header("Accept", "application/json")
    if token:
        request.add_header(TOKEN_HEADER, token)

    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
            elapsed = (time.perf_counter() - started) * 1000
            return response.status, elapsed, _extract_code(raw), None
    except urllib.error.HTTPError as e:
        # 4xx/5xx 也是有效观测（限流场景就该看到非 2xx），不算异常
        return e.code, (time.perf_counter() - started) * 1000, None, f"HTTP {e.code}"
    except Exception as e:  # noqa: BLE001 —— 压测脚本需要吞掉所有网络异常以继续统计
        return 0, (time.perf_counter() - started) * 1000, None, f"{type(e).__name__}: {e}"


def _extract_code(raw: bytes) -> str | None:
    """从响应体里取平台业务码；不是平台统一响应体（如 actuator）时返回 None。"""
    try:
        payload = json.loads(raw.decode("utf-8"))
    except Exception:  # noqa: BLE001
        return None
    if isinstance(payload, dict) and isinstance(payload.get("code"), str):
        return payload["code"]
    return None


def run_round(base_url: str,
              endpoints: list[tuple[str, str]],
              concurrency: int,
              requests_per_endpoint: int,
              token: str | None,
              timeout: float) -> tuple[list[Report], float]:
    """并发打一轮，返回每个接口的报表与本轮墙钟耗时。"""
    reports = {name: Report(name) for name, _ in endpoints}
    lock = threading.Lock()

    def worker(name: str, path: str, count: int) -> None:
        for _ in range(count):
            status, elapsed, code, error = http_get(base_url + path, token, timeout)
            with lock:
                reports[name].add(Sample(name, elapsed, status, code, error))

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(worker, name, path, requests_per_endpoint)
                   for name, path in endpoints]
        # 必须显式取结果。ThreadPoolExecutor 会把任务中的异常吞进 Future，
        # 于是 worker 一旦抛错，报表就会安静地变成「0 样本」，
        # 而墙钟耗时因为压根没干活反而极短（0.0x 秒）——
        # 极易被误读成「接口又快又稳」。这里让异常直接炸出来。
        for future in futures:
            future.result()
    wall = time.perf_counter() - started

    total_requests = len(endpoints) * requests_per_endpoint
    for report in reports.values():
        report.qps = len(report.samples) / wall if wall > 0 else 0.0
    _ = total_requests
    return list(reports.values()), wall


def fetch_json(base_url: str, path: str, token: str | None) -> tuple[dict | None, str]:
    """读取一个 JSON 接口，返回 (解析结果, 失败原因)。"""
    request = urllib.request.Request(base_url + path, method="GET")
    if token:
        request.add_header(TOKEN_HEADER, token)
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            raw = response.read()
    except urllib.error.HTTPError as e:
        return None, f"HTTP {e.code}"
    except Exception as e:  # noqa: BLE001
        return None, f"{type(e).__name__}: {e}"
    try:
        return json.loads(raw.decode("utf-8")), ""
    except Exception as e:  # noqa: BLE001
        return None, f"响应不是 JSON（{e}）：{raw[:120]!r}"


def login(base_url: str, username: str, password: str) -> tuple[str | None, str]:
    """用账号口令换取平台令牌（POST /api/v1/auth/login）。

    ⚠️ main() 的 --username/--password 分支依赖本函数存在。历史版本漏了实现，
    导致「带账号跑」这条路径直接 NameError，而 --token 手动传入时一切正常 ——
    缺陷只在某一条分支上暴露，属于最难发现的那一类。
    """
    body = json.dumps({"username": username, "password": password}).encode("utf-8")
    request = urllib.request.Request(base_url + "/api/v1/auth/login", data=body, method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read()
    except urllib.error.HTTPError as e:
        return None, f"HTTP {e.code}"
    except Exception as e:  # noqa: BLE001
        return None, f"{type(e).__name__}: {e}"

    try:
        payload = json.loads(raw.decode("utf-8"))
    except Exception as e:  # noqa: BLE001
        return None, f"响应不是 JSON（{e}）：{raw[:120]!r}"
    if payload.get("code") != "0000":
        return None, f"业务码 {payload.get('code')}：{payload.get('message')}"
    return payload["data"]["token"], ""


def print_cache_metrics(base_url: str, token: str | None) -> None:
    """打印二级缓存命中率 —— 这是判断「缓存是否真的生效」的唯一客观依据。"""
    payload, reason = fetch_json(base_url, "/api/v1/monitor/cache", token)
    if payload is None:
        print(f"\n[缓存指标] 读取失败：{reason}")
        return
    if payload.get("code") != "0000":
        print(f"\n[缓存指标] 接口返回业务码 {payload.get('code')}：{payload.get('message')}"
              "（该接口需要平台登录令牌，可用 --token 传入）")
        return

    data = payload["data"]
    print(f"\n[缓存指标] 模式 = {data.get('mode')}")
    rate_limit = data.get("rateLimit") or {}
    print(f"[限流指标] 算法 = {rate_limit.get('algorithm')}  "
          f"HTTP {rate_limit.get('httpPerMinute')}/分钟·车  "
          f"MQTT {rate_limit.get('mqttPerSecond')}/秒·车")

    rows = data.get("caches") or []
    if not rows:
        print("  （未启用二级缓存管理器，或缓存尚未被访问）")
        return
    print(f"  {'缓存名':<26}{'形态':<12}{'L1命中':>8}{'L2命中':>8}{'穿透':>8}{'命中率':>9}")
    for row in rows:
        form = "L1+L2" if row.get("distributed") else "仅L1"
        print(f"  {row.get('name', ''):<26}{form:<12}"
              f"{row.get('l1Hits', 0):>8}{row.get('l2Hits', 0):>8}"
              f"{row.get('misses', 0):>8}{str(row.get('hitRate', 0)) + '%':>9}")


def main() -> int:
    parser = argparse.ArgumentParser(description="DSSAD 云平台只读接口压测")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--concurrency", type=int, default=13,
                        help="并发线程数（约等于同时在线的前端页签数）")
    parser.add_argument("--requests-per-endpoint", type=int, default=200)
    parser.add_argument("--rounds", type=int, default=3,
                        help="重复轮数。第 1 轮是冷启动（缓存未命中），后续轮应显著变快")
    parser.add_argument("--token", default=None,
                        help="平台登录令牌（走 /api/v1/auth/login 获取，请求头 X-Token）")
    parser.add_argument("--username", default=None,
                        help="登录用户名（与 --password 一起用，脚本自动换取 --token）")
    parser.add_argument("--password", default=None, help="登录口令")
    parser.add_argument("--timeout", type=float, default=10.0)
    args = parser.parse_args()

    token = args.token
    if not token and args.username and args.password:
        token, reason = login(args.base_url, args.username, args.password)
        if not token:
            print(f"[登录] 失败：{reason}")
            return 2
        print(f"[登录] 成功，令牌长度 {len(token)}")

    print("=" * 108)
    print(f"DSSAD 云平台只读接口压测  target={args.base_url}  并发={args.concurrency}  "
          f"每接口请求数={args.requests_per_endpoint}  鉴权={'已登录' if token else '未登录'}")
    print("=" * 108)

    print_cache_metrics(args.base_url, token)

    baseline: dict[str, tuple[float, float]] = {}
    for round_index in range(1, args.rounds + 1):
        reports, wall = run_round(args.base_url, HOT_ENDPOINTS, args.concurrency,
                                  args.requests_per_endpoint, token, args.timeout)
        print(f"\n--- 第 {round_index} 轮（墙钟 {wall:.2f}s）---")
        for report in reports:
            print(report.render())
            if round_index == 1:
                baseline[report.endpoint] = (report.percentile(50), report.percentile(95))

        if round_index > 1:
            # 为什么同时看 P50 与 P95：
            #   P50 反映「绝大多数请求走的是哪条路径」——缓存命中与未命中会明显分层；
            #   P95 在本机（笔记本/共享 CI）上受 GC 与调度抖动主导，波动可达 ±30%，
            #   单看 P95 会把噪声当成结论。
            print("  缓存收益（相对第 1 轮冷启动）:")
            print(f"    {'接口':<12}{'P50 前':>9}{'P50 后':>9}{'P50 变化':>11}"
                  f"{'P95 前':>9}{'P95 后':>9}{'P95 变化':>11}")
            for report in reports:
                p50_before, p95_before = baseline.get(report.endpoint, (float("nan"), float("nan")))
                p50_after, p95_after = report.percentile(50), report.percentile(95)
                if any(v != v for v in (p50_before, p50_after, p95_before, p95_after)):
                    continue
                d50 = (p50_before - p50_after) / p50_before * 100
                d95 = (p95_before - p95_after) / p95_before * 100
                print(f"    {report.endpoint:<12}{p50_before:>8.1f}ms{p50_after:>8.1f}ms{d50:>10.1f}%"
                      f"{p95_before:>8.1f}ms{p95_after:>8.1f}ms{d95:>10.1f}%")

    # ⚠️ 必须用 token（可能来自 --username 登录），不能用 args.token ——
    # 后者在「用账号登录」的用法下恒为 None，于是收尾这次读取必然返回业务码 2001，
    # 恰好把「压测跑完后最需要看的那份缓存命中率」变成一行失败提示。
    print_cache_metrics(args.base_url, token)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
