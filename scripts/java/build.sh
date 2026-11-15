#!/usr/bin/env bash
# Linux构建完整Web程序；Node仅构建时需要，运行JAR不需要Node或Python。
set -euo pipefail
# 根据脚本位置定位仓库，正确处理含空格的绝对路径。
repository_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd -- "$repository_root/web"
# 锁文件固定直接和间接依赖，任何测试失败都会停止发布构建。
npm ci --no-audit --no-fund
npm test
npm run build
cd -- "$repository_root"
mvn -B --no-transfer-progress -f backend/pom.xml verify
printf '%s\n' '网页与Java验证完成，完整Web程序位于 backend/target。'
