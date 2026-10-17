#!/usr/bin/env bash
# 用途：运行已构建的控制平面；启动前由调用方导出本地配置。
# 不在这里构建或迁移外部数据；Flyway 随应用启动执行正式版本迁移。
set -euo pipefail
# Caller exports variables from .env; this script never prints them.
exec java -jar apps/platform-api/target/platform-api-0.1.0.jar
