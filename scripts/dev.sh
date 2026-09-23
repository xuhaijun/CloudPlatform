#!/usr/bin/env bash
# ============================================================================
#  dev.sh —— 本地开发一键启动（后端 spring-boot:run + 前端 Vite dev server）
#
#  用法：
#    bash scripts/dev.sh            # 后端 + 前端一起起（后台，就绪后返回）
#    bash scripts/dev.sh --api      # 只起后端
#    bash scripts/dev.sh --web      # 只起前端（后端需已在跑）
#    bash scripts/dev.sh --stop     # 停止本脚本启动的全部进程
#    bash scripts/dev.sh --logs     # 跟随查看两个日志
#
#  与 run-local.sh 的区别：
#    dev.sh   —— 面向「改代码」，跑 classes 目录、支持热重启，用于日常开发
#    run-local.sh —— 面向「验产物」，跑打好的 jar，用于上线前终验
# ============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

START_API=0
START_WEB=0
DO_STOP=0
DO_LOGS=0

while [ $# -gt 0 ]; do
  case "$1" in
    --api)     START_API=1 ;;
    --web)     START_WEB=1 ;;
    --stop)    DO_STOP=1 ;;
    --logs)    DO_LOGS=1 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *)         die "未知参数：$1（用 --help 查看用法）" ;;
  esac
  shift
done

# 不带参数时两个都起
if [ "$START_API" = "0" ] && [ "$START_WEB" = "0" ] && [ "$DO_STOP" = "0" ] && [ "$DO_LOGS" = "0" ]; then
  START_API=1; START_WEB=1
fi

mkdir -p "$RUNTIME_DIR"
API_PID_FILE="$RUNTIME_DIR/dev-api.pid"
WEB_PID_FILE="$RUNTIME_DIR/dev-web.pid"
API_LOG="$RUNTIME_DIR/dev-api.log"
WEB_LOG="$RUNTIME_DIR/dev-web.log"

kill_pid_file() {
  local file="$1" name="$2" pid
  [ -f "$file" ] || { warn "$name：无 pid 文件，跳过"; return 0; }
  pid="$(cat "$file")"
  if kill -0 "$pid" 2>/dev/null; then
    # spring-boot:run 会派生子进程，先杀进程组再兜底杀 pid
    kill -TERM "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    sleep 3
    kill -9 "-$pid" 2>/dev/null || true
    kill -9 "$pid" 2>/dev/null || true
    ok "$name 已停止（pid $pid）"
  else
    warn "$name：进程 $pid 已不存在"
  fi
  rm -f "$file"
}

if [ "$DO_STOP" = "1" ]; then
  kill_pid_file "$WEB_PID_FILE" "前端"
  kill_pid_file "$API_PID_FILE" "后端"
  exit 0
fi

if [ "$DO_LOGS" = "1" ]; then
  info "Ctrl+C 退出（后端：$API_LOG / 前端：$WEB_LOG）"
  tail -n 30 -f "$API_LOG" "$WEB_LOG"
  exit 0
fi

# --------------------------------------------------------------------------
# 后端
# --------------------------------------------------------------------------
if [ "$START_API" = "1" ]; then
  JAVA_HOME="$(find_java_home 21)" || die "未找到 JDK 21+"
  export JAVA_HOME
  MVN_BIN="$(resolve_mvn)" || die "未找到 Maven"
  export MVN_BIN

  if port_in_use "$APP_PORT"; then
    warn "端口 $APP_PORT 已被占用，后端跳过启动（先 bash scripts/dev.sh --stop 或换端口）"
  else
    info "启动后端：mvn spring-boot:run（profile=local，端口 $APP_PORT）"
    (
      cd "$SERVER_DIR"
      env -u SERVER__PORT -u PORT -u http_proxy -u https_proxy \
          -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY \
        "$MVN_BIN" -B spring-boot:run \
          -Dspring-boot.run.profiles=local \
          -Dspring-boot.run.arguments="--server.port=$APP_PORT"
    ) > "$API_LOG" 2>&1 &
    echo "$!" > "$API_PID_FILE"

    if wait_http "http://127.0.0.1:$APP_PORT/api/v1/monitor/health-check" 200 120; then
      ok "后端就绪：http://127.0.0.1:$APP_PORT"
      info "H2 控制台：http://127.0.0.1:$APP_PORT/h2-console"
      info "模拟器已开启（local profile）：可直接注入事故/故障报文"
    else
      err "后端在 120 秒内未就绪，日志尾部："
      tail -n 30 "$API_LOG" || true
      exit 1
    fi
  fi
fi

# --------------------------------------------------------------------------
# 前端
# --------------------------------------------------------------------------
if [ "$START_WEB" = "1" ]; then
  require_file "$WEB_DIR/package.json"
  if [ ! -d "$WEB_DIR/node_modules" ]; then
    info "首次启动，安装前端依赖（npm ci）..."
    ( cd "$WEB_DIR" && npm ci )
  fi
  if port_in_use "$WEB_PORT"; then
    warn "端口 $WEB_PORT 已被占用，前端跳过启动"
  else
    info "启动前端：npm run dev（端口 $WEB_PORT，/api 已代理到 $APP_PORT）"
    ( cd "$WEB_DIR" && npm run dev ) > "$WEB_LOG" 2>&1 &
    echo "$!" > "$WEB_PID_FILE"

    READY=0
    for _ in $(seq 1 40); do
      if http_code "http://127.0.0.1:$WEB_PORT/" 2 | grep -q '^[23]'; then READY=1; break; fi
      sleep 1
    done
    if [ "$READY" = "1" ]; then
      ok "前端就绪：http://127.0.0.1:$WEB_PORT（登录账号见下方提示）"
    else
      err "前端在 40 秒内未就绪，日志尾部："
      tail -n 20 "$WEB_LOG" || true
      exit 1
    fi
  fi
fi

title "开发环境已就绪"
cat <<EOF
  前端        http://127.0.0.1:$WEB_PORT
  后端        http://127.0.0.1:$APP_PORT
  健康检查    http://127.0.0.1:$APP_PORT/api/v1/monitor/health-check

  日志        bash scripts/dev.sh --logs
  停止        bash scripts/dev.sh --stop

  登录账号（local profile 未设环境变量时使用开发默认值）：
    admin / dssad@2026
  生产必须通过环境变量覆盖：DSSAD_ADMIN_USER / DSSAD_ADMIN_PASSWORD
EOF
