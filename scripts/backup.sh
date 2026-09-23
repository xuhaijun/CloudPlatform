#!/usr/bin/env bash
# ============================================================================
#  backup.sh —— MySQL 一致性备份 + 备份后校验 + 轮转清理
#
#  为什么不能只是「mysqldump > xx.sql」，三条都对应一类真实事故：
#
#    1) 不加 --single-transaction：MyISAM 表会被加全局读锁，备份期间业务写全阻塞。
#       高峰期跑一次备份 = 一次故障。
#
#    2) 不校验的备份等于没有备份：mysqldump 中途被 OOM / 连接断开杀掉时，
#       留下的是一个**语法完整、内容截断**的 .sql —— 直到真的要恢复时才发现
#       「Table doesn't exist」，而此时原始数据已经没了。
#       本脚本用三重校验挡住它：gzip 完整性 / 结尾 "Dump completed" 标记 /
#       CREATE TABLE 计数与库中实际表数比对。另提供 --restore-check 做真恢复演练。
#
#    3) 不轮转的备份会先把磁盘写满，然后**应用先挂**（告警指向数据库空间不足，
#       真因却是备份文件堆积）。
#
#  用法：
#    bash scripts/backup.sh                          # 用 .env + 默认参数
#    bash scripts/backup.sh --out /data/backups --keep 30
#    bash scripts/backup.sh --restore-check           # 额外做恢复演练（会建临时库）
#    bash scripts/backup.sh --dry-run                 # 只打印将执行的动作
#    bash scripts/backup.sh --db dssad_cloud --env-file /etc/dssad/db.env
#
#  退出码（供监控系统按码分类告警）：
#    0   成功
#    10  参数/用法错误
#    20  数据库连接失败或凭据缺失
#    30  mysqldump 执行失败
#    40  备份校验失败（文件损坏 / 截断 / 表数不符）
#    45  恢复演练失败
#    50  轮转清理失败
#    60  环境缺失（找不到 mysql / mysqldump / gzip 客户端）
# ============================================================================

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

readonly E_USAGE=10
readonly E_AUTH=20
readonly E_DUMP=30
readonly E_VERIFY=40
readonly E_RESTORE=45
readonly E_ROTATE=50
readonly E_ENV=60

# ---------------------------------------------------------------------------
# 默认参数（可用命令行 / .env 覆盖）
# ---------------------------------------------------------------------------
OUT_DIR="$PROJECT_ROOT/backups"
KEEP=14                 # 保留最近 N 份；0 = 不清理（生产不建议）
DRY_RUN=0
DO_RESTORE_CHECK=0
USE_GZIP=1
WITH_BINLOG_POSITION=1
ENV_FILE=""
DB_NAME_OVERRIDE=""

usage() {
  sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --out)                 OUT_DIR="${2:?--out 需要目录参数}"; shift 2 ;;
    --keep)                KEEP="${2:?--keep 需要份数}"; shift 2 ;;
    --db)                  DB_NAME_OVERRIDE="${2:?--db 需要库名}"; shift 2 ;;
    --env-file)            ENV_FILE="${2:?--env-file 需要文件路径}"; shift 2 ;;
    --restore-check)       DO_RESTORE_CHECK=1; shift ;;
    --no-gzip)             USE_GZIP=0; shift ;;
    --no-binlog-position)  WITH_BINLOG_POSITION=0; shift ;;
    --dry-run)             DRY_RUN=1; shift ;;
    -h|--help)             usage ;;
    *) err "未知参数：$1"; exit $E_USAGE ;;
  esac
done

case "$KEEP" in
  ''|*[!0-9]*) err "--keep 必须是非负整数：$KEEP"; exit $E_USAGE ;;
esac

# ---------------------------------------------------------------------------
# 1. 定位客户端工具
# ---------------------------------------------------------------------------
# Windows 上 MySQL 常不在 PATH 里；按**版本号**倒序取最高版本
# （按字典序会先命中 5.7/8.0 之类的旧版本，与本机多 JDK 的坑同源）
find_mysql_tool() {
  local name="$1" candidate
  if has_cmd "$name"; then command -v "$name"; return 0; fi
  for candidate in $(ls -d "/c/Program Files/MySQL/MySQL Server "*/bin/"$name".exe 2>/dev/null | sort -Vr); do
    [ -x "$candidate" ] && { printf '%s' "$candidate"; return 0; }
  done
  for candidate in $(ls -d /usr/bin/"$name" /usr/local/bin/"$name" /usr/local/mysql/bin/"$name" 2>/dev/null); do
    [ -x "$candidate" ] && { printf '%s' "$candidate"; return 0; }
  done
  return 1
}

MYSQL_BIN="$(find_mysql_tool mysql || true)"
MYSQLDUMP_BIN="$(find_mysql_tool mysqldump || true)"

if [ -z "$MYSQLDUMP_BIN" ] || [ -z "$MYSQL_BIN" ]; then
  err "找不到 mysql / mysqldump 客户端，无法备份。"
  err "  Debian/Ubuntu : apt install mysql-client"
  err "  RHEL/CentOS     : yum install mysql"
  err "  Windows         : 安装 MySQL Server（自带 bin/mysqldump.exe）或加入 PATH"
  exit $E_ENV
fi

if [ "$USE_GZIP" = "1" ] && ! has_cmd gzip; then
  err "找不到 gzip，无法压缩。可用 --no-gzip 生成未压缩备份。"
  exit $E_ENV
fi

# ---------------------------------------------------------------------------
# 2. 读取数据库连接参数（命令行环境变量 > --env-file > 仓库 .env）
# ---------------------------------------------------------------------------
[ -n "$ENV_FILE" ] && load_env_file "$ENV_FILE"
[ -z "$ENV_FILE" ] && load_env_file "$PROJECT_ROOT/.env"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME_OVERRIDE:-${DB_NAME:-}}"
DB_USERNAME="${DB_USERNAME:-}"
DB_PASSWORD="${DB_PASSWORD:-}"

if [ -z "$DB_NAME" ] || [ -z "$DB_USERNAME" ]; then
  err "缺少数据库连接信息：需要 DB_NAME 与 DB_USERNAME"
  err "  可在仓库 .env 中配置，或用 --db / 环境变量传入。参考 deploy/systemd/dssad-cloud-platform.env.example"
  exit $E_USAGE
fi

# 库名会进入 shell 与 SQL 字符串，先做白名单校验再使用
case "$DB_NAME" in
  *[!A-Za-z0-9_]*|'')
    err "库名只允许字母/数字/下划线，实际为：$DB_NAME（拒绝继续，避免 SQL 注入与误删）"
    exit $E_USAGE ;;
esac

# ---------------------------------------------------------------------------
# 3. 输出目录校验
# ---------------------------------------------------------------------------
case "$OUT_DIR" in
  ''|'/'|'.'|'..') err "备份目录不合法：$OUT_DIR"; exit $E_USAGE ;;
esac
if [ "$DRY_RUN" != "1" ]; then
  mkdir -p "$OUT_DIR" || { err "无法创建备份目录：$OUT_DIR"; exit $E_USAGE; }
fi

# ---------------------------------------------------------------------------
# 4. 工具函数
# ---------------------------------------------------------------------------
# 统一走 --protocol=TCP：Windows 上 --host=localhost 会走命名管道，
# 在 WSL/Git Bash/容器里常常连不上，反而报「Can't connect」而看起来像数据库没起。
mysql_cli() {
  env MYSQL_PWD="$DB_PASSWORD" "$MYSQL_BIN" \
    --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USERNAME" \
    --protocol=TCP --default-character-set=utf8mb4 "$@"
}

mysql_query() {
  mysql_cli -N -B -e "$1" 2>/dev/null
}

# 打印一条**不含明文口令**的命令行（--dry-run 与日志用）
print_safe_cmd() {
  info "  MYSQL_PWD=*** $MYSQLDUMP_BIN --host=$DB_HOST --port=$DB_PORT --user=$DB_USERNAME \\"
  info "      --single-transaction --quick --routines --triggers --events \\"
  info "      --source-data=2 --hex-blob --set-gtid-purged=OFF $DB_NAME${GZIP_SUFFIX:- }"
}

fail_summary() {
  printf 'BACKUP_FAIL stage=%s reason=%s\n' "$1" "$2" >&2
}

# ---------------------------------------------------------------------------
# 5. 连通性与基线信息
# ---------------------------------------------------------------------------
title "备份前检查"

TABLE_COUNT_BEFORE="$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME'")"
if [ -z "$TABLE_COUNT_BEFORE" ]; then
  # 再试一次，这次把错误暴露出来（上一次静默是为了让命令行干净）
  err "无法连接数据库或读取 $DB_NAME："
  mysql_cli -e "SELECT 1" 2>&1 | tail -3 | sed 's/^/    /'
  fail_summary "connect" "cannot-read-schema"
  exit $E_AUTH
fi
ok "已连接 $DB_USERNAME@$DB_HOST:$DB_PORT/$DB_NAME"
info "库中表数量：$TABLE_COUNT_BEFORE"

SERVER_VERSION="$(mysql_query 'SELECT VERSION()')"
info "服务器版本：$SERVER_VERSION"

if [ "$TABLE_COUNT_BEFORE" -eq 0 ]; then
  warn "库中没有任何表 —— 备份会得到一个空转储。请确认 --db / DB_NAME 指向的是正确的库。"
fi

# ---------------------------------------------------------------------------
# 6. 执行备份
# ---------------------------------------------------------------------------
TS="$(date +%Y%m%d_%H%M%S)"
BASENAME="${DB_NAME}_${TS}"
if [ "$USE_GZIP" = "1" ]; then
  OUT_FILE="$OUT_DIR/$BASENAME.sql.gz"
  GZIP_SUFFIX="| gzip -9 > $BASENAME.sql.gz"
else
  OUT_FILE="$OUT_DIR/$BASENAME.sql"
fi
META_FILE="$OUT_FILE.meta"

# 参数逐条说明（评审时不必去翻文档）：
#   --single-transaction  在 REPEATABLE READ 下开启一致性快照，只对 InnoDB 有效，
#                         全程不加表锁（这是「一致性」与「不阻塞业务」的唯一兼顾方式）
#   --quick               逐行流式读取，不把整表结果集堆进客户端内存（大表必加）
#   --routines/--triggers/--events  存储过程、触发器、事件调度器，漏了就要人工补
#   --source-data=2       以注释形式记录 binlog 位点，便于按时间点恢复（PITR）
#   --hex-blob            二进制列以十六进制导出，避免字符集转换损坏
#   --set-gtid-purged=OFF 避免把 GTID 集合写进转储，否则恢复到新实例会报错
DUMP_ARGS=(
  --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USERNAME"
  --protocol=TCP
  --single-transaction
  --quick
  --routines --triggers --events
  --default-character-set=utf8mb4
  --hex-blob
  --set-gtid-purged=OFF
)
[ "$WITH_BINLOG_POSITION" = "1" ] && DUMP_ARGS+=(--source-data=2)

title "执行备份"
info "目标文件：$OUT_FILE"
if [ "$DRY_RUN" = "1" ]; then
  print_safe_cmd
  info "（--dry-run：未执行任何写入）"
  exit 0
fi

START_EPOCH="$(date +%s)"
if [ "$USE_GZIP" = "1" ]; then
  # 单独开一个子 shell 打开 pipefail：否则 `mysqldump | gzip` 的退出码来自 gzip，
  # mysqldump 崩溃会被 gzip 的成功（它只是读到 EOF）掩盖成「备份成功」。
  ( set -o pipefail; env MYSQL_PWD="$DB_PASSWORD" "$MYSQLDUMP_BIN" "${DUMP_ARGS[@]}" "$DB_NAME" \
      | gzip -9 > "$OUT_FILE" )
  DUMP_RC=$?
else
  env MYSQL_PWD="$DB_PASSWORD" "$MYSQLDUMP_BIN" "${DUMP_ARGS[@]}" "$DB_NAME" > "$OUT_FILE"
  DUMP_RC=$?
fi

if [ "$DUMP_RC" -ne 0 ]; then
  err "mysqldump 失败（退出码 $DUMP_RC），已删除不完整的文件"
  rm -f "$OUT_FILE"
  fail_summary "dump" "exit-$DUMP_RC"
  exit $E_DUMP
fi
DURATION=$(( $(date +%s) - START_EPOCH ))

SIZE_BYTES="$(wc -c < "$OUT_FILE" | tr -d ' ')"
# 自适应单位：小库显示 KiB、大库显示 GiB。固定用 MiB 会把小库显示成「0.0 MiB」，
# 让「备份成功但文件几乎是空的」这类问题看起来正常。
SIZE_HUMAN="$(awk -v b="$SIZE_BYTES" 'BEGIN {
  if (b >= 1073741824) printf "%.2f GiB", b / 1073741824;
  else if (b >= 1048576) printf "%.1f MiB", b / 1048576;
  else if (b >= 1024) printf "%.1f KiB", b / 1024;
  else printf "%d B", b;
}')"
ok "备份完成：$SIZE_HUMAN，耗时 ${DURATION}s"

# ---------------------------------------------------------------------------
# 7. 备份校验（三重）
# ---------------------------------------------------------------------------
title "备份校验"

decompress_cat() {
  if [ "$USE_GZIP" = "1" ]; then gzip -dc "$1"; else cat "$1"; fi
}

# 校验 1：文件非空
if [ "$SIZE_BYTES" -lt 1024 ]; then
  err "备份文件小于 1KB，几乎可以确定是空转储或写入失败"
  fail_summary "verify" "too-small"
  exit $E_VERIFY
fi
ok "① 文件大小合理（$SIZE_BYTES 字节）"

# 校验 2：压缩流完整性
if [ "$USE_GZIP" = "1" ]; then
  if ! gzip -t "$OUT_FILE" 2>/dev/null; then
    err "gzip 完整性校验失败：文件已损坏（磁盘满/写入中断的典型表现）"
    fail_summary "verify" "gzip-corrupt"
    exit $E_VERIFY
  fi
  ok "② gzip 流完整性通过"
else
  ok "② 未压缩模式，跳过 gzip 校验"
fi

# 校验 3：结尾必须是 mysqldump 的正常收尾标记
# 只取末尾 4KB 判断，不必为一个大文件做全量解压
TAIL_MARK="$(decompress_cat "$OUT_FILE" 2>/dev/null | tail -c 4096 | tr -d '\r')"
if ! printf '%s' "$TAIL_MARK" | grep -q "Dump completed"; then
  err "备份缺少 mysqldump 的 'Dump completed' 收尾标记 —— 这是**被截断**的转储"
  err "（进程被杀 / 连接断开 / 磁盘写满都会留下这种文件，恢复时才报错就太晚了）"
  fail_summary "verify" "truncated"
  exit $E_VERIFY
fi
ok "③ 收尾标记存在（Dump completed）→ 转储完整落盘"

# 校验 4：表数量比对（防止「连错库」或「只导出了一部分」）
CREATE_COUNT="$(decompress_cat "$OUT_FILE" | grep -c '^CREATE TABLE' || true)"
if [ "$CREATE_COUNT" != "$TABLE_COUNT_BEFORE" ]; then
  err "表数量不符：转储内 CREATE TABLE = $CREATE_COUNT，库中实际表数 = $TABLE_COUNT_BEFORE"
  err "常见原因：库名写错导了别的库；或备份期间有人 DROP/CREATE 了表（请重跑一次确认）"
  fail_summary "verify" "table-count-mismatch($CREATE_COUNT!=$TABLE_COUNT_BEFORE)"
  exit $E_VERIFY
fi
ok "④ CREATE TABLE 计数与库中表数一致（$CREATE_COUNT 张表）"

# binlog 位点（PITR 用；缺失只提示不失败，因为它依赖权限与 binlog 配置）
BINLOG_POS="$(decompress_cat "$OUT_FILE" | grep -m1 -E 'CHANGE MASTER TO|CHANGE REPLICATION SOURCE TO' | tr -d '\r' || true)"
if [ -n "$BINLOG_POS" ]; then
  info "binlog 位点：$(printf '%s' "$BINLOG_POS" | sed 's/^-- *//')"
else
  warn "转储中未找到 binlog 位点（--no-binlog-position 或账号缺少 RELOAD 权限）→ 无法做时间点恢复"
fi

# ---------------------------------------------------------------------------
# 8. 可选：恢复演练（唯一的硬证据）
# ---------------------------------------------------------------------------
RESTORE_RESULT="skipped"
if [ "$DO_RESTORE_CHECK" = "1" ]; then
  title "恢复演练（--restore-check）"
  TMP_DB="${DB_NAME}_restore_check_${TS}"
  case "$TMP_DB" in
    *[!A-Za-z0-9_]*) err "临时库名不合法，已中止：$TMP_DB"; exit $E_RESTORE ;;
  esac
  info "临时库：$TMP_DB（演练结束后自动删除）"

  if ! mysql_cli -e "DROP DATABASE IF EXISTS \`$TMP_DB\`; CREATE DATABASE \`$TMP_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" 2>/tmp/.dssad_restore_err; then
    err "创建临时库失败："; tail -3 /tmp/.dssad_restore_err | sed 's/^/    /'; rm -f /tmp/.dssad_restore_err
    fail_summary "restore-check" "create-tmpdb-failed"
    exit $E_RESTORE
  fi
  rm -f /tmp/.dssad_restore_err

  RESTORE_RC=0
  decompress_cat "$OUT_FILE" | mysql_cli "$TMP_DB" >/dev/null 2>/tmp/.dssad_restore_err || RESTORE_RC=$?
  if [ "$RESTORE_RC" -ne 0 ]; then
    err "恢复失败（退出码 $RESTORE_RC）："; tail -5 /tmp/.dssad_restore_err | sed 's/^/    /'
    rm -f /tmp/.dssad_restore_err
    mysql_cli -e "DROP DATABASE IF EXISTS \`$TMP_DB\`" 2>/dev/null || true
    fail_summary "restore-check" "import-failed"
    exit $E_RESTORE
  fi
  rm -f /tmp/.dssad_restore_err

  RESTORED_COUNT="$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TMP_DB'")"
  if [ "$RESTORED_COUNT" != "$TABLE_COUNT_BEFORE" ]; then
    err "恢复后表数不符：$RESTORED_COUNT != $TABLE_COUNT_BEFORE —— 这份备份**不可用**"
    mysql_cli -e "DROP DATABASE IF EXISTS \`$TMP_DB\`" 2>/dev/null || true
    fail_summary "restore-check" "restored-count-mismatch($RESTORED_COUNT)"
    exit $E_RESTORE
  fi
  mysql_cli -e "DROP DATABASE IF EXISTS \`$TMP_DB\`" 2>/dev/null || true
  ok "恢复演练通过：恢复到临时库后表数一致（$RESTORED_COUNT 张表），临时库已删除"
  RESTORE_RESULT="ok"
fi

# ---------------------------------------------------------------------------
# 9. 元数据（给恢复演练与审计留证据，不靠翻日志猜）
# ---------------------------------------------------------------------------
# 归档名直接取真实文件名，不做字符串拼接 ——
# 之前版本用 `$BASENAME.sql$(... printf 'gz')` 拼出过 `.sqlgz`（漏了分隔点），
# 元数据里那个错误的文件名会在恢复演练时把人带偏。
ARCHIVE_NAME="$(basename "$OUT_FILE")"
BINLOG_POS_CLEAN="$(printf '%s' "${BINLOG_POS:-}" | sed 's/^-- *//' | tr -d '\r')"
[ -n "$BINLOG_POS_CLEAN" ] || BINLOG_POS_CLEAN="n/a"

cat > "$META_FILE" <<EOF
# DSSAD 备份元数据（由 scripts/backup.sh 生成；恢复时请连同此文件一起归档）
file=$ARCHIVE_NAME
database=$DB_NAME
created_at=$(date -Iseconds)
source=$DB_HOST:$DB_PORT
server_version=$SERVER_VERSION
size_bytes=$SIZE_BYTES
size_human=$SIZE_HUMAN
tables=$TABLE_COUNT_BEFORE
create_table_statements=$CREATE_COUNT
gzip_integrity=$([ "$USE_GZIP" = "1" ] && printf 'ok' || printf 'n/a')
dump_completed=yes
binlog_position=$BINLOG_POS_CLEAN
restore_check=$RESTORE_RESULT
duration_seconds=$DURATION
tool=$(env MYSQL_PWD=x "$MYSQLDUMP_BIN" --version 2>/dev/null | head -1)
EOF
ok "元数据已写入 $(basename "$META_FILE")"

# ---------------------------------------------------------------------------
# 10. 轮转清理
# ---------------------------------------------------------------------------
title "轮转清理"
if [ "$KEEP" = "0" ]; then
  warn "--keep 0：跳过清理。备份会无限增长，请确保另有外部归档与磁盘告警"
else
  # 只匹配本脚本自己的命名规则，绝不做「按通配符删除目录内容」这种危险动作；
  # *.meta 与 .sql(.gz) 同生共死，一起统计、一起删除。
  EXISTING="$(ls -1t "$OUT_DIR"/"$DB_NAME"_*.sql.gz "$OUT_DIR"/"$DB_NAME"_*.sql 2>/dev/null | wc -l | tr -d ' ')"
  info "当前备份份数：$EXISTING，保留策略：最近 $KEEP 份"

  if [ "$EXISTING" -le "$KEEP" ]; then
    ok "未超出保留份数，无需清理"
  else
    TO_DELETE="$(ls -1t "$OUT_DIR"/"$DB_NAME"_*.sql.gz "$OUT_DIR"/"$DB_NAME"_*.sql 2>/dev/null | tail -n +$((KEEP + 1)))"
    DELETED=0
    while IFS= read -r victim; do
      [ -n "$victim" ] || continue
      info "  删除过期备份：$(basename "$victim")"
      rm -f "$victim" "$victim.meta" || { err "删除失败：$victim"; fail_summary "rotate" "rm-failed"; exit $E_ROTATE; }
      DELETED=$((DELETED + 1))
    done <<< "$TO_DELETE"
    ok "已清理 $DELETED 份过期备份，当前保留 $KEEP 份"
  fi
fi

# ---------------------------------------------------------------------------
# 11. 结果摘要（一行机器可读，供监控系统提取）
# ---------------------------------------------------------------------------
title "结果"
info "文件：$OUT_FILE"
printf 'BACKUP_OK file=%s size_bytes=%s tables=%s duration_s=%s restore_check=%s kept=%s\n' \
  "$OUT_FILE" "$SIZE_BYTES" "$TABLE_COUNT_BEFORE" "$DURATION" "$RESTORE_RESULT" "$KEEP"
exit 0
