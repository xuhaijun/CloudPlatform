#!/usr/bin/env bash
# ============================================================================
#  mqtt-watch.sh —— MQTT 通道存活探针（把「收不到数据」变成可告警的信号）
#
#  存在的理由（对应运维手册 O-01 / 部署说明 E-01）：
#    平台的 liveness / readiness 以及 /api/v1/monitor/health-check 在 Broker 断开时
#    **一律返回成功** —— 这是刻意设计（断链时查询类接口仍应可用）。
#    后果是：所有探针全绿、监控面板 100% 可用，平台却一条车端数据都收不到。
#    本脚本消费的是**只反映数据通道**的信号：GET /api/v1/monitor/mqtt-health
#    （DOWN 时返回 HTTP 503，因此连只会看状态码的探针也能用）。
#
#  为什么探的是业务端点而不是 /actuator/health/mqtt：
#    ① /actuator/health 聚合端点**始终**包含所有已注册的 HealthIndicator，
#       把通道做成健康组会让聚合端点一起变红 → 用 /actuator/health 判活的编排会摘流/重启，
#       而 Broker 故障重启应用毫无用处（实测结论，见 application.yml 的注释）；
#    ② 生产 Nginx 把 /actuator/ 整体 404（正确的收敛姿势），为此单开一个 location
#       等于把治理面撕开一道口子。业务端点天然已被 /api/ 规则代理。
#
#  用法：
#    bash scripts/mqtt-watch.sh                          # 默认 http://127.0.0.1:8080
#    bash scripts/mqtt-watch.sh --base-url http://10.0.0.5:8080
#    bash scripts/mqtt-watch.sh --deep                   # 额外登录读通道指标，校验积压/丢弃
#    bash scripts/mqtt-watch.sh --deep --backlog 2000     # 覆盖积压阈值（默认 5000）
#    bash scripts/mqtt-watch.sh --allow-disabled         # 未启用 MQTT 视为正常（本地/开发态）
#    bash scripts/mqtt-watch.sh --quiet                  # 只输出一行机器可读结果
#
#  退出码（故障分类码，不是 Nagios 三段码；分级映射见运维手册 4.3 表）：
#     0  正常
#    10  通道未启用（mqtt.enabled=false）—— 本地/开发态属正常，生产态属配置错误
#    20  通道未连通（DOWN / RECONNECTING / 凭据致命错误）
#    30  队列积压超阈值（链路在但处理不过来 —— 摘实例只会更糟，应扩容或查消费瓶颈）
#    40  已发生丢弃（数据不可恢复，最严重）
#    90  探针侧问题：端点不可达、非预期状态码、鉴权失败（404 = 实例版本过旧，缺探针端点）
#    91  参数或环境错误
#
#  设计约束：
#   1. **无状态**：不在本地记「连续失败次数」。防抖动是告警平台的职责
#      （Prometheus 用 `for: 2m`，Zabbix 用 trigger 的连续次数），
#      写进脚本会变成又一份需要同步维护的隐藏状态。
#   2. 回环请求一律绕过代理（本机注入了 http_proxy，会把 127.0.0.1 也转发成 502）。
#   3. --deep 需要管理令牌（读的是内部运行数据）；主探针刻意不需要凭据，
#      因为实例不健康时正是最需要能访问的时候。
# ============================================================================
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

BASE_URL="http://127.0.0.1:${APP_PORT}"
USER_NAME="${DSSAD_ADMIN_USER:-admin}"
USER_PASSWORD="${DSSAD_ADMIN_PASSWORD:-dssad@2026}"
BACKLOG_THRESHOLD=5000
DEEP=0
ALLOW_DISABLED=0
QUIET=0
SHOW_JSON=0

while [ $# -gt 0 ]; do
  case "$1" in
    --base-url)       BASE_URL="${2%/}"; shift ;;
    --user)           USER_NAME="$2"; shift ;;
    --password)       USER_PASSWORD="$2"; shift ;;
    --backlog)        BACKLOG_THRESHOLD="$2"; shift ;;
    --deep)           DEEP=1 ;;
    --allow-disabled) ALLOW_DISABLED=1 ;;
    --quiet)          QUIET=1 ;;
    --json)           SHOW_JSON=1 ;;
    -h|--help)        sed -n '2,48p' "$0"; exit 0 ;;
    *)                die "未知参数：$1（用 --help 查看用法）" ;;
  esac
  shift
done

case "$BACKLOG_THRESHOLD" in
  ''|*[!0-9]*) die "--backlog 需要非负整数，实际收到：$BACKLOG_THRESHOLD" ;;
esac

say() { [ "$QUIET" = "1" ] || printf '%s\n' "$*"; }

# 机器可读结果行（一行，便于被监控系统正则提取）
emit() {
  printf 'MQTT_WATCH base=%s status=%s state=%s reconnect=%s inbound=%s offline=%s pendingAck=%s dropped=%s threshold=%s reason=%s result=%s\n' \
    "$BASE_URL" "${STATUS:-unknown}" "${STATE:-unknown}" "${RECONNECT:-0}" "${INBOUND:-0}" "${OFFLINE:-0}" \
    "${PENDING_ACK:-0}" "${DROPPED:-0}" "$BACKLOG_THRESHOLD" "${REASON:-none}" "$1"
}

finish() {
  local code="$1" result="$2"
  emit "$result"
  exit "$code"
}

WATCH_URL="$BASE_URL/api/v1/monitor/mqtt-health"

# ---------------------------------------------------------------------------
# 1. 通道探针端点（无需令牌；这是本探针的主信号）
# ---------------------------------------------------------------------------
CODE="$(http_code "$WATCH_URL" 5)"
BODY="$(api_get "$WATCH_URL")"

[ "$SHOW_JSON" = "1" ] && say "raw: $BODY"

STATUS="$(json_field "$BODY" status)"
REASON="$(json_field "$BODY" reason)"
STATE="$(json_field "$BODY" state)"
RECONNECT="$(json_num "$BODY" reconnectAttempts)"; RECONNECT="${RECONNECT:-0}"

case "$CODE" in
  200|503) : ;;
  000)
    err "端点不可达：$WATCH_URL（服务未启动？端口不对？或 http_proxy 干扰）"
    finish 90 unreachable
    ;;
  404)
    err "端点不存在（404）：$WATCH_URL"
    err "含义：目标实例的版本早于本探针端点（该端点随运维手册一同引入），或前面挂了不认识该路径的网关。"
    err "排查：① 确认部署的 jar 是当前版本；② 直连后端端口复测；③ 检查 Nginx 是否只代理了部分 /api/ 前缀。"
    finish 90 endpoint-missing
    ;;
  401|403)
    err "鉴权失败（$CODE）：该端点应免令牌，出现鉴权说明前面挂了未放行的网关或安全组件"
    finish 90 unauthorized
    ;;
  *)
    err "非预期状态码 $CODE（期望 200=UP/DISABLED 或 503=DOWN）"
    say "响应：$BODY"
    finish 90 unexpected-status
    ;;
esac

if [ "$STATUS" = "DISABLED" ]; then
  if [ "$ALLOW_DISABLED" = "1" ]; then
    say "OK：MQTT 未启用（--allow-disabled，本地/开发态正常）"
    finish 0 ok-disabled
  fi
  err "MQTT 未启用（dssad.mqtt.enabled=false）—— 生产环境属配置错误：平台不会收到任何车端数据"
  finish 10 disabled
fi

if [ "$CODE" = "503" ] || [ "$STATUS" = "DOWN" ]; then
  err "通道 DOWN：state=$STATE reason=${REASON:-none}（已重连 $RECONNECT 次）"
  case "$REASON" in
    credentials-or-config-failed)
      err "→ 致命错误（凭据换取失败等），重连不会自愈：查凭据有效期、监管平台可达性、Broker 地址" ;;
    *)
      err "→ 链路断开，应用按指数退避自动重连（2s→60s）；持续 >2 分钟再告警，避免依赖抖动误报" ;;
  esac
  finish 20 channel-down
fi

say "OK：通道连通（state=$STATE，重连 $RECONNECT 次）"

# ---------------------------------------------------------------------------
# 2. --deep：登录后读通道指标，校验积压与丢弃
#    为什么默认不做：需要管理令牌，探针应尽量无凭据；积压/丢弃属「业务健康度」，
#    按需深探即可（cron 里可每 5 分钟一次，而主探针每分钟一次）。
# ---------------------------------------------------------------------------
if [ "$DEEP" = "1" ]; then
  TOKEN="$(api_login "$BASE_URL" "$USER_NAME" "$USER_PASSWORD")"
  if [ -z "$TOKEN" ]; then
    err "深度检查失败：无法登录（账号/口令不对，或服务未就绪）"
    finish 90 auth-failed
  fi

  METRICS="$(api_get "$BASE_URL/api/v1/monitor/mqtt" "$TOKEN")"
  [ "$SHOW_JSON" = "1" ] && say "metrics: $METRICS"

  if ! printf '%s' "$METRICS" | grep -q '"code":"0000"'; then
    err "通道指标接口返回异常：$METRICS"
    finish 90 metrics-unavailable
  fi

  INBOUND="$(json_num "$METRICS" inboundQueueSize)"; INBOUND="${INBOUND:-0}"
  OFFLINE="$(json_num "$METRICS" offlineQueueSize)"; OFFLINE="${OFFLINE:-0}"
  PENDING_ACK="$(json_num "$METRICS" pendingAck)"; PENDING_ACK="${PENDING_ACK:-0}"
  CONNECTED="$(json_field "$METRICS" connected)"
  drop_in="$(json_num "$METRICS" inboundDropped)"; drop_in="${drop_in:-0}"
  drop_pressure="$(json_num "$METRICS" offlineDroppedByPressure)"; drop_pressure="${drop_pressure:-0}"
  drop_low="$(json_num "$METRICS" offlineDroppedLowPriority)"; drop_low="${drop_low:-0}"
  drop_telemetry="$(json_num "$METRICS" telemetryDropped)"; drop_telemetry="${drop_telemetry:-0}"
  DROPPED=$((drop_in + drop_pressure + drop_low + drop_telemetry))

  say "指标：inbound=$INBOUND offline=$OFFLINE pendingAck=$PENDING_ACK dropped=$DROPPED（阈值 $BACKLOG_THRESHOLD）"

  # 数据已丢 → 最严重，优先报（不可恢复，且往往意味着上游已按超时重发过）
  if [ "$DROPPED" -gt 0 ]; then
    err "已发生丢弃 $DROPPED 条（入站 $drop_in / 压力 $drop_pressure / 低优先级 $drop_low / 遥测 $drop_telemetry）"
    finish 40 dropped
  fi

  BACKLOG=$((INBOUND + OFFLINE))
  if [ "$BACKLOG" -gt "$BACKLOG_THRESHOLD" ]; then
    err "队列积压 $BACKLOG 超过阈值 $BACKLOG_THRESHOLD（inbound=$INBOUND offline=$OFFLINE）"
    err "→ 链路是通的，是消费跟不上：查消费线程是否被慢 SQL/磁盘拖住，或考虑扩容消费线程"
    finish 30 backlog
  fi

  if [ "$CONNECTED" != "true" ]; then
    # 指标接口说连不上，而探针端点说 UP，说明两次请求之间发生了抖动
    err "指标接口显示未连接（connected=$CONNECTED），与探针端点结果不一致 —— 可能刚发生一次闪断"
    finish 20 channel-down
  fi

  say "深度检查通过：无积压、无丢弃"
fi

finish 0 ok
