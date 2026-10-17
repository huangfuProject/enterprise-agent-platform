#!/usr/bin/env bash
# 用途：启动单 Runtime HTTP 服务，配置由调用方导出。
# 必须保持单 worker；同目录的独占锁不是多实例共享 SQLite 的分布式锁。
# 不开启自动重载，避免开发重启被误认为任意运行中的执行可以安全恢复。
set -euo pipefail
source scripts/dev-env.sh
exec "$RUNTIME_PYTHON" -m uvicorn agent_runtime.api:create_app --factory --host "${RUNTIME_BIND_ADDRESS:-127.0.0.1}" --port "${RUNTIME_PORT:-8000}" --workers 1
