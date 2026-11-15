#!/usr/bin/env bash
# Linux 前台启动入口，使用与 Windows 相同的 Java 21 目标 JAR。
set -euo pipefail
# 根据脚本位置定位程序根目录。
repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
# 当前版本默认产物；版本升级时用 --jar 指定已校验的新 JAR。
jar_path="$repository_root/backend/target/archive-service-0.1.0.jar"
# 首次初始化才要求保留初始管理员密码。
initialize_admin=false
while (($#)); do
    case "$1" in
        --jar) [[ $# -ge 2 ]] || { printf '%s\n' '--jar 后必须提供文件路径' >&2; exit 2; }; jar_path="$2"; shift 2 ;;
        --initialize-admin) initialize_admin=true; shift ;;
        *) printf '未知参数：%s\n' "$1" >&2; exit 2 ;;
    esac
done
# 所有持久化目标均要求明确配置，不打印密码。
: "${IMS_DB_URL:?请设置 IMS_DB_URL}"
: "${IMS_DB_USER:?请设置 IMS_DB_USER}"
: "${IMS_DB_PASSWORD:?请设置 IMS_DB_PASSWORD}"
: "${IMS_STORAGE_ROOT:?请设置 IMS_STORAGE_ROOT}"
: "${IMS_BACKUP_ROOT:?请设置 IMS_BACKUP_ROOT}"
[[ "$IMS_DB_URL" == jdbc:postgresql://* ]] || { printf '%s\n' 'IMS_DB_URL 必须是 PostgreSQL JDBC 地址' >&2; exit 2; }
[[ "$IMS_STORAGE_ROOT" == /* && "$IMS_BACKUP_ROOT" == /* ]] || { printf '%s\n' '附件和备份目录必须是绝对路径' >&2; exit 2; }
# 字符长度仅做启动前检查，服务端还会执行最终密码校验。
initial_password="${IMS_BOOTSTRAP_ADMIN_PASSWORD:-}"
if "$initialize_admin" && [[ -z "$initial_password" ]]; then
    printf '%s\n' '首次初始化必须显式设置 IMS_BOOTSTRAP_ADMIN_PASSWORD' >&2; exit 2
fi
if [[ -n "$initial_password" && ( ${#initial_password} -lt 12 || ${#initial_password} -gt 128 ) ]]; then
    printf '%s\n' '初始管理员密码长度必须是 12 至 128 个字符' >&2; exit 2
fi
# 优先使用显式 JAVA_HOME，不拼接任意 JVM 命令字符串。
java_executable="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$java_executable" >/dev/null || { printf '%s\n' '找不到 Java，请安装 JDK 21' >&2; exit 2; }
[[ -f "$jar_path" ]] || { printf '%s\n' '找不到指定 JAR，请先运行 mvn verify' >&2; exit 2; }
# 切换目录前固定相对 JAR 参数的含义。
jar_path="$(cd -- "$(dirname -- "$jar_path")" && pwd)/$(basename -- "$jar_path")"
cd -- "$repository_root"
printf '%s\n' '正在启动 Java 档案服务，请以启动日志及 /health 检查为准。'
# 保留信号和退出码，便于以后交给 systemd 管理。
exec "$java_executable" -Dfile.encoding=UTF-8 -jar "$jar_path"
