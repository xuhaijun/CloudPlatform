#!/usr/bin/env bash
# ============================================================================
#  health.sh —— 全链路冒烟（把「服务是好的」变成可执行断言）
#
#  这是本套脚本里价值最高的一个：它不只探活，而是逐条断言「业务能写、能读、
#  鉴权有效、登出真失效」。任何一次重构只要破坏了这些契约，这里立刻报警。
#
#  用法：
#    bash scripts/health.sh                       # 默认 http://127.0.0.1:8080
#    bash scripts/health.sh --base-url http://127.0.0.1:18080
#    bash scripts/health.sh --user admin --password 'dssad@2026'
#    bash scripts/health.sh --no-simulator        # 跳过「注入事故场景」相关断言
#
#  什么时候用 --no-simulator：对**生产或生产态实例**冒烟时。
#  模拟器在 prod profile 下是关闭的（dssad.simulator.enabled=false），
#  而且即便能开也不该用 —— 它会凭空写入事故与故障记录，污染监管取证链路。
#
#  退出码：0 = 全部断言通过；1 = 有失败项
#
#  钉子用例（刻意保留，勿删）：
#   [2] 无令牌访问受保护接口 → 必须被拒（鉴权不可被绕过）
#   [15] 登出后旧令牌 → 必须失效（令牌可主动吊销，而非只是「前端删了本地缓存」）
# ============================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

BASE_URL="http://127.0.0.1:${APP_PORT}"
USER_NAME="${DSSAD_ADMIN_USER:-admin}"
USER_PASSWORD="${DSSAD_ADMIN_PASSWORD:-dssad@2026}"
USE_SIMULATOR=1

while [ $# -gt 0 ]; do
  case "$1" in
    --base-url)     BASE_URL="${2%/}"; shift ;;
    --user)         USER_NAME="$2"; shift ;;
    --password)     USER_PASSWORD="$2"; shift ;;
    --no-simulator) USE_SIMULATOR=0 ;;
    -h|--help)      sed -n '2,24p' "$0"; exit 0 ;;
    *)              die "未知参数：$1（用 --help 查看用法）" ;;
  esac
  shift
done

PASS=0
FAIL=0
SKIPPED=0
STEP_NO=0

# 断言：$1=断言描述；其余=「实际值 期望值」对，全部相等才通过
expect_eq() {
  local desc="$1" actual="$2" expected="$3"
  STEP_NO=$((STEP_NO + 1))
  if [ "$actual" = "$expected" ]; then
    ok "[$STEP_NO] $desc（$actual）"
    PASS=$((PASS + 1))
  else
    err "[$STEP_NO] $desc —— 期望「$expected」，实际「$actual」"
    FAIL=$((FAIL + 1))
  fi
}

# 断言：正文包含某子串
expect_contains() {
  local desc="$1" haystack="$2" needle="$3"
  STEP_NO=$((STEP_NO + 1))
  case "$haystack" in
    *"$needle"*) ok "[$STEP_NO] $desc"; PASS=$((PASS + 1)) ;;
    *)           err "[$STEP_NO] $desc —— 响应中未找到「$needle」"; FAIL=$((FAIL + 1)) ;;
  esac
}

# 断言：正文**不含**某子串（用于抓「列表接口把大字段也带出来了」这类性能回归）
expect_absent() {
  local desc="$1" haystack="$2" needle="$3"
  STEP_NO=$((STEP_NO + 1))
  case "$haystack" in
    *"$needle"*) err "[$STEP_NO] $desc —— 响应中出现了不应存在的「$needle」"; FAIL=$((FAIL + 1)) ;;
    *)           ok "[$STEP_NO] $desc"; PASS=$((PASS + 1)) ;;
  esac
}

# 跳过一项（用于「该环境本就不适用」的断言，不计为失败）
skip_step() {
  STEP_NO=$((STEP_NO + 1))
  warn "[$STEP_NO] 跳过：$1"
  SKIPPED=$((SKIPPED + 1))
}

title "冒烟目标：$BASE_URL"

# ---------------------------------------------------------------------------
# 1. 公开健康检查（不需要令牌，编排探活就用它）
# ---------------------------------------------------------------------------
BODY="$(api_get "$BASE_URL/api/v1/monitor/health-check")"
expect_contains "健康检查返回成功码 0000" "$BODY" '"code":"0000"'

# ---------------------------------------------------------------------------
# 2. 未授权必须被拒（钉子用例）
# ---------------------------------------------------------------------------
BODY="$(api_get "$BASE_URL/api/v1/vehicles?page=1&size=1")"
expect_contains "无令牌访问 /api/v1/vehicles 被拒（2001）" "$BODY" '"code":"2001"'

# ---------------------------------------------------------------------------
# 3. 登录
# ---------------------------------------------------------------------------
TOKEN="$(api_login "$BASE_URL" "$USER_NAME" "$USER_PASSWORD")"
STEP_NO=$((STEP_NO + 1))
if [ -n "$TOKEN" ]; then
  ok "[$STEP_NO] 登录成功，令牌长度 ${#TOKEN}"
  PASS=$((PASS + 1))
else
  err "[$STEP_NO] 登录失败 —— 检查账号口令，或服务是否已就绪"
  FAIL=$((FAIL + 1))
  err "登录失败，后续断言无法继续"
  exit 1
fi

# ---------------------------------------------------------------------------
# 4. 业务写入 → 读出：注入一次完整事故场景，再从查询接口读回来
#    （若目标是生产态实例，用 --no-simulator 跳过：模拟器会写入假事故，
#      污染监管取证链路）
# ---------------------------------------------------------------------------
if [ "$USE_SIMULATOR" = "0" ]; then
  skip_step "注入事故场景与写后读断言（--no-simulator）"
  skip_step "事故详情可查"
  skip_step "事故详情归属车辆正确"
  skip_step "车辆主档可查"
else
  HC_VIN="LSVAAHEALTH000001"
  SCEN="$(curl -s --noproxy '*' --max-time 15 -X POST \
          "$BASE_URL/api/v1/simulator/scenario/accident?vin=$HC_VIN" 2>/dev/null || true)"
  EVENT_ID="$(json_field "$SCEN" eventId)"
  expect_contains "注入事故场景（写入链路：解析→校验→去重→业务→ACK→留痕）" "$SCEN" '"code":"0000"'

  if [ -z "$EVENT_ID" ]; then
    skip_step "事故详情可查（模拟器未启用，常见于非 local profile）"
    skip_step "事故详情归属车辆正确"
    skip_step "车辆主档可查"
  else
    DETAIL="$(api_get "$BASE_URL/api/v1/events/accidents/$EVENT_ID" "$TOKEN")"
    expect_contains "事故详情可查（写后即可读）" "$DETAIL" '"code":"0000"'
    expect_contains "事故详情归属车辆正确" "$DETAIL" "$HC_VIN"

    VEH="$(api_get "$BASE_URL/api/v1/vehicles/$HC_VIN" "$TOKEN")"
    expect_contains "车辆主档可查（报文自动建档）" "$VEH" '"code":"0000"'
  fi
fi

# ---------------------------------------------------------------------------
# 5. 列表契约 + 性能钉子
# ---------------------------------------------------------------------------
LIST="$(api_get "$BASE_URL/api/v1/vehicles?page=1&size=5" "$TOKEN")"
expect_contains "车辆列表返回成功码" "$LIST" '"code":"0000"'
expect_contains "列表使用分页结构（page/size）" "$LIST" '"page"'
# 性能钉子：列表接口若把轨迹点数组带出来，响应体会从 KB 级涨到 MB 级
expect_absent   "车辆列表未夹带轨迹大字段" "$LIST" '"trackPoints"'

# ---------------------------------------------------------------------------
# 6. 监控与看板接口
# ---------------------------------------------------------------------------
expect_contains "缓存指标接口可用" "$(api_get "$BASE_URL/api/v1/monitor/cache" "$TOKEN")" '"code":"0000"'
expect_contains "MQTT 监控接口可用" "$(api_get "$BASE_URL/api/v1/monitor/mqtt" "$TOKEN")" '"code":"0000"'
expect_contains "总览大屏接口可用" "$(api_get "$BASE_URL/api/v1/dashboard/overview" "$TOKEN")" '"code":"0000"'

# ---------------------------------------------------------------------------
# 7. 登出后旧令牌必须失效（钉子用例）
#    断言的是「服务端真的吊销了令牌」，而不是「前端把本地缓存删了」
# ---------------------------------------------------------------------------
curl -s --noproxy '*' --max-time 10 -X POST -H "X-Token: $TOKEN" \
     "$BASE_URL/api/v1/auth/logout" >/dev/null 2>&1 || true

AFTER="$(api_get "$BASE_URL/api/v1/vehicles?page=1&size=1" "$TOKEN")"
expect_contains "登出后旧令牌被拒绝（令牌可主动吊销，非前端自欺）" "$AFTER" '"code":"2001"'

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
title "冒烟结果"
printf '  通过 %s 项，失败 %s 项，跳过 %s 项\n' "$PASS" "$FAIL" "$SKIPPED"
if [ "$FAIL" -eq 0 ]; then
  ok "全链路冒烟通过"
  exit 0
fi
err "存在失败断言，请按上面 [编号] 逐条排查"
exit 1
