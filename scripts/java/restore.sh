#!/usr/bin/env bash
# Linux 隔离恢复：新空库和不存在的新附件目录，成功后仍须人工验收、切换。
set -euo pipefail
# 备份文件由调用方明确指定，没有默认数据库或凭据。
[[ $# -eq 1 ]] || { printf '%s\n' '用法：bash scripts/java/restore.sh <完整备份包路径>' >&2; exit 2; }
backup_file="$1"
# 按脚本位置定位恢复源码，仓库路径可包含中文。
repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
restore_source="$repository_root/backend/src/main/java/com/company/ims/operations/OfflineRestore.java"
# 独立恢复配置不能误用当前服务连接参数。
: "${IMS_RESTORE_DB_URL:?请设置新空库的 IMS_RESTORE_DB_URL}"
: "${IMS_RESTORE_DB_USER:?请设置 IMS_RESTORE_DB_USER}"
: "${IMS_RESTORE_DB_PASSWORD:?请设置 IMS_RESTORE_DB_PASSWORD}"
: "${IMS_RESTORE_STORAGE_ROOT:?请设置全新目录 IMS_RESTORE_STORAGE_ROOT}"
[[ "$IMS_RESTORE_STORAGE_ROOT" == /* ]] || { printf '%s\n' '恢复目录必须是绝对路径' >&2; exit 2; }
[[ ! -e "$IMS_RESTORE_STORAGE_ROOT" && ! -L "$IMS_RESTORE_STORAGE_ROOT" ]] || { printf '%s\n' '恢复目标已存在，请指定全新目录' >&2; exit 2; }
[[ -f "$backup_file" && -f "$restore_source" ]] || { printf '%s\n' '缺少备份包或 OfflineRestore.java' >&2; exit 2; }
# 优先使用已配置 JDK，不用 shell 二次解释命令参数。
java_executable="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$java_executable" >/dev/null || { printf '%s\n' '找不到 JDK 21' >&2; exit 2; }
printf '%s\n' '将验证备份并恢复到新空库和新附件目录；当前服务配置不会自动切换。'
# 保留工具真实退出码，非零时停止后续切换。
exec "$java_executable" -Dfile.encoding=UTF-8 --source 21 "$restore_source" "$backup_file"
