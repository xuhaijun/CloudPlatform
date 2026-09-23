#!/usr/bin/env bash
# ============================================================================
#  run-local.sh —— 用**成品 jar**在本机起服（可选后台 + 优雅停止）
#
#  存在的理由：`mvn spring-boot:run` 跑的是 classes 目录，跳过不了任何打包环节；
#  用 jar 起服能提前暴露「开发能跑、打出来的包起不来」这类问题（缺资源、
#  MANIFEST 错、Lombok 被误打进包等）。
#
#  用法：
#    bash scripts/run-local.sh                 # 前台，local profile（H2 文件库，零外部依赖）
#    bash scripts/run-local.sh --bg            # 后台，就绪后返回（轮询到健康接口 200）
#    bash scripts/run-local.sh --stop          # 优雅停止后台实例（SIGTERM → 等待 → SIGKILL）
#    bash scripts/run-local.sh --prod          # prod profile（需 MySQL/Redis/Broker + 环境变量）
#    bash scripts/run-local.sh --port 18080    # 换端口（端口冲突时用）
#
#  退出码：0 成功；1 启动失败（会打印日志尾部）
# ============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

PROFILE="local"
RUN_BG=0
STOP=0
PORT="$APP_PORT"
EXTRA_ARGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --bg)        RUN_BG=1 ;;
    --stop)      STOP=1 ;;
    --prod)      PROFILE="prod" ;;
    --port)      PORT="${2:?--port 需要一个端口号}"; shift ;;
    --local)     PROFILE="local" ;;
    -h|--help)   sed -n '2,20p' "$0"; exit 0 ;;
    *)           EXTRA_ARGS+=("$1") ;;
  esac
  shift
done

PID_FILE="$RUNTIME_DIR/app.pid"
LOG_FILE="$RUNTIME_DIR/app-$PORT.log"
mkdir -p "$RUNTIME_DIR"

# --------------------------------------------------------------------------
# 停止分支
# --------------------------------------------------------------------------
stop_app() {
  if [ ! -f "$PID_FILE" ]; then
    warn "未找到 $PID_FILE，说明没有本脚本启动的后台实例"
    return 0
  fi
  local pid; pid="$(cat "$PID_FILE")"
  if ! kill -0 "$pid" 2>/dev/null; then
    warn "记录中的进程 $pid 已不存在，清理 pid 文件"
    rm -f "$PID_FILE"; return 0
  fi
  info "向进程 $pid 发送 SIGTERM（应用配置了优雅停机，等待其释放连接）..."
  kill -TERM "$pid" 2>/dev/null || true
  for _ in $(seq 1 15); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    warn "15 秒内未退出，强制结束"
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
  ok "已停止"
}

[ "$STOP" = "1" ] && { stop_app; exit 0; }

# --------------------------------------------------------------------------
# 启动分支
# --------------------------------------------------------------------------
JAVA_HOME="$(find_java_home 21)" || die "未找到 JDK 21+"
export JAVA_HOME
JAVA_BIN="$JAVA_HOME/bin/java"
[ -x "$JAVA_BIN" ] || JAVA_BIN="$JAVA_HOME/bin/java.exe"
[ -x "$JAVA_BIN" ] || die "找不到可执行 java：$JAVA_HOME/bin/java"

if [ ! -f "$JAR_FILE" ]; then
  die "缺少构建产物 $JAR_FILE，先执行：bash scripts/build.sh"
fi
# 传给原生 java 的路径必须是 Windows 原生形式（/d/... 会报 Unable to access jarfile）
JAR_NATIVE="$(to_native_path "$JAR_FILE")"

# 端口预检：已在运行就直接退出，避免「以为重启了其实还是旧实例」
if port_in_use "$PORT"; then
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    warn "端口 $PORT 上已有本脚本启动的实例（pid $(cat "$PID_FILE")）。要重启请先 --stop"
  else
    warn "端口 $PORT 已被其它进程占用（换端口：bash scripts/run-local.sh --port 18080）"
  fi
  exit 1
fi

PROFILE_ARGS=(--spring.profiles.active="$PROFILE" --server.port="$PORT")

if [ "$PROFILE" = "prod" ]; then
  # prod 是「生产参数终验」，必须提供真实配置；缺项时这里就报错，而不是起一个半残实例
  load_env_file "$PROJECT_ROOT/.env"
  MISSING=()
  for v in DB_USERNAME DB_PASSWORD DSSAD_ENTERPRISE_ID DSSAD_MQTT_BROKER_URL \
           DSSAD_REGULATORY_BASE_URL DSSAD_STORAGE_PUBLIC_URL DSSAD_SRS_URL; do
    [ -z "$(eval "printf '%s' \"\${$v:-}\"")" ] && MISSING+=("$v")
  done
  if [ "${#MISSING[@]}" -gt 0 ]; then
    err "prod profile 缺少必填环境变量：${MISSING[*]}"
    info "可参照 deploy/systemd/dssad-cloud-platform.env.example 写一份 $PROJECT_ROOT/.env（已 gitignore）"
    exit 1
  fi
  [ -z "${DSSAD_ADMIN_PASSWORD:-}" ] && warn "未设置 DSSAD_ADMIN_PASSWORD，将使用开发默认口令（生产不可接受）"
  info "prod profile：请确认 MySQL/Redis/MQTT Broker 均已就绪"
else
  # local profile 零外部依赖：H2 文件库 + MQTT 关闭 + 模拟器开启，全部在 application-local.yml 里。
  #
  # 注意：令牌由 TokenService 签发后存入 StateStore（默认内存实现），因此**重启后登录态会丢失**，
  # 这是设计如此（内存实现不落盘），不是 bug —— 重新登录即可；多实例共享登录态需开 Redis
  # （dssad.redis.enabled=true），那时令牌会落在 Redis 里。
  info "local profile：重启后需重新登录（令牌存于内存 StateStore）"
fi

if [ "${#EXTRA_ARGS[@]}" -gt 0 ]; then
  PROFILE_ARGS+=("${EXTRA_ARGS[@]}")
fi

# JAVA_OPTS 可按需覆盖（容器/systemd 里由环境变量注入）
: "${JAVA_OPTS:=-Xms256m -Xmx1g -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai}"
# shellcheck disable=SC2206
JAVA_OPTS_ARR=($JAVA_OPTS)

info "profile=$PROFILE  port=$PORT  jar=$JAR_FILE"

# 关键：清掉外部注入的 SERVER__PORT / 代理变量。
#  - SERVER__PORT 会通过 Spring 的 relaxed binding 覆盖 server.port，导致「指定了端口却不生效」
#  - http_proxy 会让健康探测走代理返回 502，误判为「服务挂了」
clean_env=(env -u SERVER__PORT -u PORT
           -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY)
export NO_PROXY="$NO_PROXY"

if [ "$RUN_BG" = "1" ]; then
  "${clean_env[@]}" "$JAVA_BIN" "${JAVA_OPTS_ARR[@]}" -jar "$JAR_NATIVE" \
      "${PROFILE_ARGS[@]}" > "$LOG_FILE" 2>&1 &
  APP_PID=$!
  echo "$APP_PID" > "$PID_FILE"
  info "已后台启动（pid $APP_PID），日志：$LOG_FILE"

  # 轮询就绪：用应用自己的公开健康接口，而不是只看进程是否存活
  READY=0
  for _ in $(seq 1 75); do
    if ! kill -0 "$APP_PID" 2>/dev/null; then break; fi
    code="$(http_code "http://127.0.0.1:$PORT/api/v1/monitor/health-check" 3)"
    [ "$code" = "200" ] && { READY=1; break; }
    sleep 2
  done

  if [ "$READY" = "1" ]; then
    ok "服务已就绪：http://127.0.0.1:$PORT"
    info "健康检查 : http://127.0.0.1:$PORT/api/v1/monitor/health-check"
    [ "$PROFILE" = "local" ] && info "H2 控制台 : http://127.0.0.1:$PORT/h2-console（JDBC URL 见 application-local.yml）"
    info "停止服务 : bash scripts/run-local.sh --stop"
    exit 0
  fi

  err "服务在 150 秒内未就绪，日志尾部："
  tail -n 40 "$LOG_FILE" || true
  kill -9 "$APP_PID" 2>/dev/null || true
  rm -f "$PID_FILE"
  exit 1
fi

# 前台模式
info "前台运行，Ctrl+C 停止"
trap 'info "收到中断，正在停止..."; kill -TERM "${APP_PID:-0}" 2>/dev/null || true' INT TERM
"${clean_env[@]}" "$JAVA_BIN" "${JAVA_OPTS_ARR[@]}" -jar "$JAR_NATIVE" "${PROFILE_ARGS[@]}" &
APP_PID=$!
echo "$APP_PID" > "$PID_FILE"
wait "$APP_PID" || true
rm -f "$PID_FILE"
info "已退出"
