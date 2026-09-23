#!/usr/bin/env bash
# ============================================================================
#  _common.sh —— 所有入口脚本共用的公共库（不要直接执行本文件，用 source 引入）
#
#  设计要点（每一条都对应一次真实踩坑）：
#   1. Git Bash 里 PATH 可能丢掉 /usr/bin，表现为 grep/awk/dirname 全部 not found，
#      而退出码仍是 0 —— 看起来像「命令没输出」而不是报错。故先修 PATH。
#   2. 本机注入了 http_proxy，连 127.0.0.1 的探测也会被转发并返回 502，
#      极易误判为「服务没起来」。故所有回环请求统一加 --noproxy '*'。
#   3. 本机装了多个 JDK，按字母序 glob 会先命中 jdk-18 导致「不支持发行版本 21」，
#      故 JDK 候选必须按版本号排序（sort -V）取最高，并用 --version 校验。
#   4. Maven 认的是 JAVA_HOME；Git Bash 里直接敲 mvn（sh 版）会把 Windows 路径
#      传给原生 Java 报「找不到主类 Launcher」，故 Windows 上强制用 mvn.cmd。
# ============================================================================

# ---------------------------------------------------------------------------
# 0. 环境兜底：补 PATH，清代理
# ---------------------------------------------------------------------------
case ":$PATH:" in
  *:/usr/bin:*) ;;
  *) PATH="/usr/bin:/bin:$PATH"; export PATH ;;
esac

# 回环地址一律绕过代理：本机 http_proxy 会把 127.0.0.1 的请求也转发出去
export NO_PROXY="${NO_PROXY:-127.0.0.1,localhost}"
export no_proxy="$NO_PROXY"

# ---------------------------------------------------------------------------
# 1. 输出
# ---------------------------------------------------------------------------
if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
  C_BLUE=$'\033[34m'; C_BOLD=$'\033[1m'; C_RESET=$'\033[0m'
else
  C_RED=''; C_GREEN=''; C_YELLOW=''; C_BLUE=''; C_BOLD=''; C_RESET=''
fi

info()  { printf '%s[信息]%s %s\n' "$C_BLUE"  "$C_RESET" "$*"; }
ok()    { printf '%s[通过]%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn()  { printf '%s[警告]%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
err()   { printf '%s[失败]%s %s\n' "$C_RED"   "$C_RESET" "$*" >&2; }
die()   { err "$*"; exit 1; }
title() { printf '\n%s%s== %s ==%s\n' "$C_BOLD" "$C_BLUE" "$*" "$C_RESET"; }
# 待办清单：计数器由调用方维护，这里只负责格式化
todo()  { printf '  %s✗%s %s\n' "$C_RED" "$C_RESET" "$*"; }
done_() { printf '  %s✓%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }

# ---------------------------------------------------------------------------
# 2. 路径
# ---------------------------------------------------------------------------
# 本文件所在目录（scripts/），其上一级即仓库根
SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPTS_DIR/.." && pwd)"
SERVER_DIR="$PROJECT_ROOT/cloud-platform-server"
WEB_DIR="$PROJECT_ROOT/cloud-platform-web"
DEPLOY_DIR="$PROJECT_ROOT/deploy"
RUNTIME_DIR="$PROJECT_ROOT/.run"      # 本地生产态运行的 pid/日志，已在 .gitignore

# 项目与端口常量（改这里即可，脚本各处不再散落魔法数字）
APP_NAME="dssad-cloud-platform"
JAR_FILE="$SERVER_DIR/target/$APP_NAME.jar"
WEB_DIST="$WEB_DIR/dist"
APP_PORT="${APP_PORT:-8080}"
WEB_PORT="${WEB_PORT:-5173}"

# ---------------------------------------------------------------------------
# 3. 平台判定
# ---------------------------------------------------------------------------
case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*) IS_WINDOWS=1 ;;
  *)                    IS_WINDOWS=0 ;;
esac

# ---------------------------------------------------------------------------
# 4. 工具解析
# ---------------------------------------------------------------------------
# 版本号比较：取 a、b 中较新者（用 sort -V，避免字典序把 18 排到 21 前面）
_version_max() { printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n1; }

# 读取一个 JDK 根目录的 java 版本（只输出主版本号，如 21）
_java_major() {
  local root="$1" bin
  if [ -x "$root/bin/java" ]; then bin="$root/bin/java"
  elif [ -x "$root/bin/java.exe" ]; then bin="$root/bin/java.exe"
  elif [ -x "$root/java" ]; then bin="$root/java"
  else return 1; fi
  # 从 -version 的输出里抓 major：兼容 "21.0.10" 与旧的 "1.8.0_x"
  "$bin" -version 2>&1 | head -n1 \
    | sed -n 's/.*version "\([0-9]*\)\.\([0-9]*\).*/\1.\2/p' \
    | awk -F. '{ if ($1=="1") print $2; else print $1 }' | head -n1
}

# 找到可用的 JDK（要求主版本 >= 21）。输出 JAVA_HOME 路径；找不到返回 1。
find_java_home() {
  local min="${1:-21}" candidate major best=""     best_major=0
  local candidates=()

  # 显式指定优先
  [ -n "${JAVA_HOME:-}" ] && candidates+=("${JAVA_HOME%/}")
  # 本机常见安装位置（按平台区分）
  if [ "$IS_WINDOWS" = "1" ]; then
    for base in "/c/Program Files/Java" "/c/Program Files/Eclipse Adoptium" \
                "/c/Program Files/Microsoft" "/c/Program Files (x86)/Java" \
                "$HOME/.workbuddy/binaries/java" "/c/Java"; do
      [ -d "$base" ] || continue
      while IFS= read -r d; do [ -n "$d" ] && candidates+=("$d"); done \
        < <(find "$base" -maxdepth 2 -type d \( -name 'jdk*' -o -name 'jre*' -o -name '*jdk-*' \) 2>/dev/null)
    done
  else
    for base in /usr/lib/jvm /opt/java /usr/java /opt/jdk*; do
      [ -d "$base" ] || continue
      while IFS= read -r d; do [ -n "$d" ] && candidates+=("$d"); done \
        < <(find "$base" -maxdepth 2 -type d \( -name 'jdk*' -o -name 'java-*' \) 2>/dev/null)
    done
  fi

  for candidate in "${candidates[@]}"; do
    [ -n "$candidate" ] && [ -d "$candidate" ] || continue
    major="$(_java_major "$candidate" 2>/dev/null || true)"
    [ -n "$major" ] || continue
    # 必须 >= 期望版本，且在满足条件的里面取最高
    if [ "$major" -ge "$min" ]; then
      if [ "$major" -gt "$best_major" ] || { [ "$major" -eq "$best_major" ] && [ -n "$best" ]; }; then
        best="$candidate"; best_major="$major"
      fi
    fi
  done

  # 兜底：PATH 上的 java 反查真实 java.home（注意它可能是版本不匹配的转发器）
  if [ -z "$best" ] && command -v java >/dev/null 2>&1; then
    local real
    real="$(java -XshowSettings:properties -version 2>&1 \
            | sed -n 's/^ *java\.home *= *//p' | head -n1)"
    if [ -n "$real" ] && [ -d "$real" ]; then
      major="$(_java_major "$real" || true)"
      [ -n "$major" ] && [ "$major" -ge "$min" ] && { best="$real"; best_major="$major"; }
    fi
  fi

  [ -n "$best" ] || return 1
  echo "$best"
}

# 解析 Maven 可执行文件。Windows 上必须用 mvn.cmd（见文件头说明）。
resolve_mvn() {
  local home
  for home in "${MAVEN_HOME:-}" "${M2_HOME:-}" \
              "/c/Program Files/apache-maven-3.9.9" \
              "$HOME/.workbuddy/binaries/maven/apache-maven-3.9.9" \
              /usr/share/maven /opt/maven; do
    [ -n "$home" ] && [ -d "$home" ] || continue
    if [ "$IS_WINDOWS" = "1" ] && [ -f "$home/bin/mvn.cmd" ]; then echo "$home/bin/mvn.cmd"; return 0; fi
    if [ -x "$home/bin/mvn" ]; then echo "$home/bin/mvn"; return 0; fi
  done
  # 兜底：直接找 PATH
  if [ "$IS_WINDOWS" = "1" ] && command -v mvn.cmd >/dev/null 2>&1; then command -v mvn.cmd; return 0; fi
  command -v mvn >/dev/null 2>&1 && { command -v mvn; return 0; }
  return 1
}

has_cmd() { command -v "$1" >/dev/null 2>&1; }

# 把 MSYS/Git Bash 路径转成 Windows 原生路径。
# 为什么必须转：java/mysql/docker 这些是**原生程序**，它们不认识 /d/xxx 形式。
# 不转的话 `java -jar` 会报「Unable to access jarfile /d/...」，而看起来像「jar 不存在」；
# bash 内建命令（cd、重定向）则能正确理解 MSYS 路径，所以只有传给原生程序的路径需要转。
to_native_path() {
  local p="$1"
  if [ "$IS_WINDOWS" = "1" ] && has_cmd cygpath; then
    cygpath -w "$p"
  else
    printf '%s' "$p"
  fi
}

# ---------------------------------------------------------------------------
# 5. 端口与 HTTP
# ---------------------------------------------------------------------------
# 端口是否被占用（用 bash 内建 /dev/tcp，不依赖 netstat/ss/lsof）
port_in_use() {
  local port="$1"
  (exec 3<>"/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1 || return 1
  exec 3<&- 2>/dev/null || true
  return 0
}

# 取 HTTP 状态码。用 -w 把状态码拼在响应末尾，**不落临时文件** ——
# Windows 上「curl 写文件 + 立刻 cat」存在可见时序延迟，会出现「文件还没生成」的误判。
http_code() {
  local url="$1" timeout="${2:-5}"
  curl -s -o /dev/null -w '%{http_code}' --noproxy '*' --max-time "$timeout" "$url" 2>/dev/null || echo "000"
}

# 轮询直到返回期望状态码。$1=url $2=期望码 $3=最长等待秒
wait_http() {
  local url="$1" expect="${2:-200}" max_wait="${3:-90}" waited=0 code
  while [ "$waited" -lt "$max_wait" ]; do
    code="$(http_code "$url" 3)"
    [ "$code" = "$expect" ] && return 0
    sleep 2; waited=$((waited + 2))
  done
  return 1
}

# 带令牌的请求，只输出响应体（回环地址自动绕过代理）
api_get() {
  local url="$1" token="${2:-}"
  if [ -n "$token" ]; then
    curl -s --noproxy '*' --max-time 15 -H "X-Token: $token" "$url" 2>/dev/null
  else
    curl -s --noproxy '*' --max-time 15 "$url" 2>/dev/null
  fi
}

# 从 JSON 里取字段（不做完整解析，够用即可；依赖 python3 时更稳）
json_field() {
  local json="$1" key="$2"
  printf '%s' "$json" | tr ',' '\n' | grep -o "\"$key\":\"[^\"]*\"" | head -n1 | cut -d'"' -f4
}

api_login() {
  local base="$1" user="$2" password="$3"
  local body
  body="$(curl -s --noproxy '*' --max-time 10 -X POST \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$password\"}" \
    "$base/api/v1/auth/login" 2>/dev/null || true)"
  json_field "$body" token
}

# 从 JSON 里取**数值**字段（json_field 只匹配带引号的字符串值，取不到数字）。
# 用 `"key":` 精确匹配（前置引号保证 lastInboundQueueSize 不会被 inboundQueueSize 命中）。
json_num() {
  local json="$1" key="$2"
  printf '%s' "$json" | tr ',' '\n' \
    | sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\([-0-9.]*\).*/\1/p" | head -n1
}

# ---------------------------------------------------------------------------
# 6. 配置与密钥
# ---------------------------------------------------------------------------
# 读取 KEY=VALUE 形式的 env 文件；已存在的环境变量优先（不被文件覆盖）
load_env_file() {
  local file="$1" line key value
  [ -f "$file" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    line="${line#export }"
    key="${line%%=*}"
    value="${line#*=}"
    key="$(printf '%s' "$key" | tr -d ' \t')"
    [ -n "$key" ] || continue
    # 去掉可能的引号
    value="${value%\"}"; value="${value#\"}"
    value="${value%\'}"; value="${value#\'}"
    # 已设置的变量不覆盖（保证命令行/env 优先级最高）
    if [ -z "$(eval "printf '%s' \"\${$key:-}\"")" ]; then
      export "$key=$value"
    fi
  done < "$file"
}

# 生成随机密钥/口令（优先 openssl，其次 /dev/urandom）。
# 用途：填写 deploy/systemd/*.env.example 里的 DB_PASSWORD、DSSAD_ADMIN_PASSWORD 等，
# 避免手写弱口令。用法：bash scripts/check-env.sh --gen-secret  （见 check-env.sh 末尾）
gen_secret() {
  local len="${1:-32}"
  if has_cmd openssl; then
    openssl rand -hex "$len"
  else
    head -c "$len" /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

# ---------------------------------------------------------------------------
# 7. 前置检查小工具
# ---------------------------------------------------------------------------
require_file() { [ -f "$1" ] || die "缺少文件：$1"; }
require_dir()  { [ -d "$1" ] || die "缺少目录：$1"; }

print_versions() {
  info "JDK   : ${JAVA_HOME:-<未设置>}  版本 $(_java_major "${JAVA_HOME:-}" 2>/dev/null || echo '?')"
  [ -n "${MVN_BIN:-}" ] && info "Maven : $MVN_BIN"
  has_cmd node && info "Node  : $(node -v 2>/dev/null)"
  has_cmd npm  && info "npm   : $(npm -v 2>/dev/null)"
  has_cmd docker && info "Docker: $(docker --version 2>/dev/null || echo '未安装')"
}
