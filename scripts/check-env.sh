#!/usr/bin/env bash
# ============================================================================
#  check-env.sh —— 开发/部署环境自检
#
#  这是**第一个该跑的脚本**：环境问题会伪装成代码问题（最典型的是
#  「按字母序选中 jdk-18 → 构建报『不支持发行版本 21』」），先自检能省掉大量排错。
#
#  用法：bash scripts/check-env.sh
#  退出码：0 = 无阻塞项；1 = 存在阻塞项（清单里会用 ✗ 标出）
# ============================================================================
set -uo pipefail   # 不用 -e：自检要跑完全部检查项再汇总，不能中途退出

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

# 顺手的小工具：生成一个强随机口令/密钥，用于填 .env
#   bash scripts/check-env.sh --gen-secret [长度]
if [ "${1:-}" = "--gen-secret" ]; then
  gen_secret "${2:-32}"
  exit 0
fi

BLOCKERS=0
blocker() { todo "$*"; BLOCKERS=$((BLOCKERS + 1)); }

title "1/6 基础工具"

if JAVA_HOME="$(find_java_home 21)"; then
  export JAVA_HOME
  done_ "JDK 21+ 可用：$JAVA_HOME（major=$(_java_major "$JAVA_HOME")）"
else
  blocker "未找到 JDK 21+。本项目 java.version=21，低版本会报「不支持发行版本 21」"
fi

if MVN_BIN="$(resolve_mvn)"; then
  export MVN_BIN
  # Windows 上必须确认拿到的是 mvn.cmd：Git Bash 里 mvn(sh) 会把 MAVEN_HOME 当 Unix 路径
  # 传给原生 Java，报「找不到主类 Launcher」。
  done_ "Maven 可用：$MVN_BIN"
else
  blocker "未找到 Maven。请设置 MAVEN_HOME，或把 mvn 加入 PATH"
fi

if has_cmd node && has_cmd npm; then
  NODE_MAJOR="$(node -v | sed 's/^v\([0-9]*\).*/\1/')"
  if [ "$NODE_MAJOR" -ge 18 ]; then
    done_ "Node.js $(node -v) / npm $(npm -v)（Vite 6 要求 Node ≥ 18）"
  else
    blocker "Node.js 版本过低（$(node -v)），Vite 6 要求 ≥ 18"
  fi
else
  blocker "未找到 Node.js/npm（前端构建需要）"
fi

if has_cmd docker; then
  if docker info >/dev/null 2>&1; then
    done_ "Docker 守护进程可用：$(docker --version)"
  else
    warn "Docker CLI 已装但守护进程未启动 —— 容器相关命令（build/up）不可用，其余流程不受影响"
  fi
else
  warn "未安装 Docker —— 走「jar + systemd」部署路径即可，容器路径跳过"
fi

title "2/6 项目结构"

for f in \
  "$SERVER_DIR/pom.xml" \
  "$SERVER_DIR/src/main/resources/application.yml" \
  "$SERVER_DIR/src/main/resources/application-local.yml" \
  "$SERVER_DIR/src/main/resources/application-prod.yml" \
  "$SERVER_DIR/db/schema-mysql.sql" \
  "$WEB_DIR/package.json" \
  "$WEB_DIR/vite.config.ts"
do
  [ -f "$f" ] && done_ "存在 ${f#"$PROJECT_ROOT"/}" || blocker "缺少 ${f#"$PROJECT_ROOT"/}"
done

[ -d "$WEB_DIR/node_modules" ] && done_ "前端依赖已安装（node_modules）" \
  || warn "前端依赖未安装，先执行：cd cloud-platform-web && npm install"

[ -f "$JAR_FILE" ] && done_ "后端构建产物存在：target/$APP_NAME.jar（$(du -h "$JAR_FILE" | cut -f1)）" \
  || warn "后端未打包，先执行：bash scripts/build.sh"

[ -d "$WEB_DIST" ] && done_ "前端构建产物存在：cloud-platform-web/dist（$(du -sh "$WEB_DIST" | cut -f1)）" \
  || warn "前端未构建，先执行：bash scripts/build.sh"

title "3/6 端口占用"

for p in "$APP_PORT" "$WEB_PORT" 3306 6379 1883; do
  if port_in_use "$p"; then
    warn "端口 $p 已被占用"$([ "$p" = "$APP_PORT" ] && echo "（后端默认端口；用 APP_PORT=18080 bash scripts/run-local.sh 换端口，或先 --stop 停掉旧实例）" || true)
  else
    done_ "端口 $p 空闲"
  fi
done

title "4/6 生产配置必填项"

# prod profile 里用 ${VAR} 且无默认值的项，缺失会导致启动直接失败（fail-fast 是刻意设计）
PROD_REQUIRED=(DB_USERNAME DB_PASSWORD DSSAD_ENTERPRISE_ID DSSAD_MQTT_BROKER_URL
               DSSAD_REGULATORY_BASE_URL DSSAD_STORAGE_PUBLIC_URL DSSAD_SRS_URL)
MISSING=()
for v in "${PROD_REQUIRED[@]}"; do
  [ -z "$(eval "printf '%s' \"\${$v:-}\"")" ] && MISSING+=("$v")
done

if [ "${#MISSING[@]}" -eq 0 ]; then
  done_ "生产必填环境变量已全部提供"
else
  warn "以下生产必填变量当前未设置（只在跑 prod profile 时才需要）："
  printf '        %s\n' "${MISSING[@]}"
  info "    本机联调可先用 scripts/run-local.sh（它会自动注入本地占位值）"
fi

if [ -n "${DSSAD_ADMIN_PASSWORD:-}" ]; then
  done_ "DSSAD_ADMIN_PASSWORD 已设置（生产必须覆盖开发默认口令）"
else
  warn "未设置 DSSAD_ADMIN_PASSWORD —— 生产环境会使用开发默认口令 dssad@2026，上线前必须覆盖"
fi

title "5/6 本地端口连通性（可选依赖）"

for spec in "3306:MySQL" "6379:Redis" "1883:MQTT Broker"; do
  p="${spec%%:*}"; name="${spec#*:}"
  if port_in_use "$p"; then
    done_ "$name 可达（127.0.0.1:$p）"
  else
    info "$name 未运行（127.0.0.1:$p）—— 仅在使用该依赖的场景需要"
  fi
done

title "6/6 版本信息汇总"

print_versions

printf '\n'
if [ "$BLOCKERS" -eq 0 ]; then
  ok "环境自检通过，无阻塞项"
  exit 0
fi
err "环境自检发现 $BLOCKERS 项阻塞问题，请按上面的 ✗ 逐条处理"
exit 1
