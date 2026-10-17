#!/usr/bin/env bash
# 由入口脚本加载本地工具链路径；不读取或打印业务密钥。
# 请从仓库根目录运行。CI 不提供 .env.local，直接使用已导出的环境配置。
if [[ -f .env.local ]]; then
  source .env.local
fi
export RUNTIME_PYTHON="${RUNTIME_PYTHON:-python}"
export MAVEN_BIN="${MAVEN_BIN:-mvn}"
if [[ -n "${MAVEN_SETTINGS:-}" ]]; then
  export MAVEN_SETTINGS
fi
