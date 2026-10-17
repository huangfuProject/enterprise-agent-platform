#!/usr/bin/env bash
# 用途：稳定回归与构建，按 Ruff→Python→Java→Web 顺序验证。
# set -e 保证前一项失败就停止；真实模型用例默认由显式开关隔离。
set -euo pipefail
source scripts/dev-env.sh
"$RUNTIME_PYTHON" -m ruff check apps/agent-runtime scripts/export-contracts.py
"$RUNTIME_PYTHON" -m ruff format --check apps/agent-runtime scripts/export-contracts.py
"$RUNTIME_PYTHON" -m pytest apps/agent-runtime/tests -q
./scripts/mvn.sh -B verify
npm --prefix apps/web run build
