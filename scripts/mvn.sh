#!/usr/bin/env bash
# 统一工程入口，可通过本地配置选择 Maven 和 settings。
# 参数原样传递；exec 保留构建工具的退出码与中断信号。
set -euo pipefail
source scripts/dev-env.sh
maven_args=(-f apps/platform-api/pom.xml)
if [[ -n "${MAVEN_SETTINGS:-}" ]]; then
  maven_args=(-s "$MAVEN_SETTINGS" "${maven_args[@]}")
fi
exec "$MAVEN_BIN" "${maven_args[@]}" "$@"
