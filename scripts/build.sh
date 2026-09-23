#!/usr/bin/env bash
# ============================================================================
#  build.sh —— 统一构建入口（后端 jar / 前端 dist / 容器镜像）
#
#  用法：
#    bash scripts/build.sh                 # 后端打包（含测试与 JaCoCo 覆盖率）
#    bash scripts/build.sh --web           # 后端 + 前端一起构建
#    bash scripts/build.sh --skip-tests    # 跳过测试（仅用于本地快速试跑，不用于发布）
#    bash scripts/build.sh --docker        # 额外构建容器镜像（需要 Docker 守护进程）
#    bash scripts/build.sh --clean         # 先 mvn clean
#
#  为什么默认带测试：本项目的 164 个用例含大量「契约断言」（响应码、字段名、状态机），
#  跳过测试直接出包，等于放弃了唯一能自动发现契约被破坏的机会。
# ============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

SKIP_TESTS=0
WITH_WEB=0
WITH_DOCKER=0
DO_CLEAN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-tests) SKIP_TESTS=1 ;;
    --web)        WITH_WEB=1 ;;
    --docker)     WITH_DOCKER=1 ;;
    --clean)      DO_CLEAN=1 ;;
    -h|--help)    sed -n '2,18p' "$0"; exit 0 ;;
    *)            die "未知参数：$1（用 --help 查看用法）" ;;
  esac
  shift
done

JAVA_HOME="$(find_java_home 21)" || die "未找到 JDK 21+，请先执行 bash scripts/check-env.sh"
export JAVA_HOME
MVN_BIN="$(resolve_mvn)" || die "未找到 Maven，请先执行 bash scripts/check-env.sh"
export MVN_BIN

title "后端构建"
require_file "$SERVER_DIR/pom.xml"
info "JAVA_HOME=$JAVA_HOME"
info "Maven=$MVN_BIN"

# 默认走 verify：jacoco 的覆盖率门槛（0.60）绑在 verify 阶段，
# 用 package 会跳过门槛校验，等于放弃质量门禁。
# --skip-tests 时刻意退回 package —— 没有测试数据，门槛校验只会误报失败。
MVN_GOALS=(verify)
[ "$DO_CLEAN" = "1" ] && MVN_GOALS=(clean "${MVN_GOALS[@]}")
if [ "$SKIP_TESTS" = "1" ]; then
  MVN_GOALS=(clean package -DskipTests)
  [ "$DO_CLEAN" = "0" ] && MVN_GOALS=(package -DskipTests)
  warn "已跳过测试 —— 该产物**不可用于发布**（上限为「本地试跑」）"
fi

START_TS=$(date +%s)
( cd "$SERVER_DIR" && "$MVN_BIN" -B "${MVN_GOALS[@]}" )
ELAPSED=$(( $(date +%s) - START_TS ))

require_file "$JAR_FILE"
ok "后端打包完成（${ELAPSED}s）：cloud-platform-server/target/$APP_NAME.jar（$(du -h "$JAR_FILE" | cut -f1)）"

if [ -f "$SERVER_DIR/target/site/jacoco/index.html" ]; then
  info "覆盖率报告：cloud-platform-server/target/site/jacoco/index.html"
fi

if [ "$WITH_WEB" = "1" ]; then
  title "前端构建"
  require_file "$WEB_DIR/package.json"
  if [ ! -d "$WEB_DIR/node_modules" ]; then
    info "首次构建，安装依赖（npm ci）..."
    ( cd "$WEB_DIR" && npm ci )
  fi
  ( cd "$WEB_DIR" && npm run type-check )
  ok "类型检查通过（vue-tsc 零错误）"
  ( cd "$WEB_DIR" && npm run build )
  require_dir "$WEB_DIST"
  ok "前端构建完成：cloud-platform-web/dist（$(du -sh "$WEB_DIST" | cut -f1)）"
fi

if [ "$WITH_DOCKER" = "1" ]; then
  title "容器镜像"
  has_cmd docker || die "未找到 docker"
  docker info >/dev/null 2>&1 || die "Docker 守护进程未启动，无法构建镜像"
  ( cd "$PROJECT_ROOT" && docker build -t "$APP_NAME:local" . )
  ok "镜像构建完成：$APP_NAME:local"
fi

title "完成"
cat <<EOF
产物：
  后端  cloud-platform-server/target/$APP_NAME.jar
$([ "$WITH_WEB" = "1" ] && echo "  前端  cloud-platform-web/dist/")
$([ "$WITH_DOCKER" = "1" ] && echo "  镜像  $APP_NAME:local")

下一步：
  本地生产态运行   bash scripts/run-local.sh --bg
  全链路冒烟       bash scripts/health.sh
EOF
