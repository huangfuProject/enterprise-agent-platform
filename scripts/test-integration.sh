#!/usr/bin/env bash
# 用途：先验证真实 PostgreSQL/Redis 与 Java 构建，再运行跨进程 HTTP 故障测试。
# 使用专用 eap_p0_test；不得同时让其他调度器消费该数据库。
# 真实模型测试默认跳过，显式启用时使用独立 live 测试库。
set -euo pipefail
source scripts/dev-env.sh
# Export PostgreSQL/Redis connection settings before invoking. .env is read as data by Python tests.
RUN_DB_TESTS=true POSTGRES_DB=eap_p0_test ./scripts/mvn.sh -B verify
"$RUNTIME_PYTHON" -m pytest tests/integration -q
