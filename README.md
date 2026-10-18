# Enterprise Multi-Agent Platform

Java 控制平面与独立 Python Agent Runtime 的工程基线。当前提供任务创建、持久化、原子领取、异步执行、事件查询、幂等、取消与租约失联检测，以及受控工具、SubAgent 和 Checkpoint 验证。

项目处于 P0 技术验证阶段，适用于受控开发环境。尚未实现身份认证、租户授权、生产沙箱或真实数据接入。默认使用确定性模型进行可重复测试，真实模型通过 OpenAI 兼容接口接入。

## 环境与目录

- Java 21、Maven 3.9+。
- Python 3.12；Python 3.13 尚未验证。
- Node.js 24、npm。
- PostgreSQL 17、Redis 7.4；开发环境可使用 Docker Compose。

```text
apps/platform-api       Spring Boot / MyBatis-Plus / Flyway
apps/agent-runtime      FastAPI / DeepAgents / LangGraph
apps/web                Vue / TypeScript / Vite
packages/contracts/v1   JSON Schema 与 OpenAPI
infrastructure/docker   PostgreSQL、Redis 和可选 MinIO 配置
scripts                 构建、启动、测试与契约导出
tests/integration       跨进程与故障注入测试
docs/api                接口与执行语义
docs/adr                架构决策
docs/p0                 实现架构和能力边界
```

具体依赖版本以 `pom.xml`、`requirements.lock` 和 `package-lock.json` 为准。

## 安装

从仓库根目录执行：

```bash
python -m venv .venv
source .venv/bin/activate
export RUNTIME_PYTHON=python
python -m pip install -r apps/agent-runtime/requirements.lock
python -m pip install --no-deps -e apps/agent-runtime
npm --prefix apps/web ci --no-audit --no-fund
./scripts/mvn.sh -B verify
```

脚本默认使用 `python` 和 `mvn`。自定义工具链可以通过环境变量或未跟踪的 `.env.local` 配置：

```bash
RUNTIME_PYTHON=/path/to/venv/bin/python
MAVEN_BIN=/path/to/maven/bin/mvn
MAVEN_SETTINGS=/path/to/settings.xml
```

设置 `MAVEN_SETTINGS` 后，每次 Maven 调用都会显式使用该配置；未设置时使用 Maven 默认设置。不要在本地工具链文件中复制业务凭证。

离线环境应提前准备 Wheelhouse、Maven 仓库缓存、npm 缓存及镜像。完整离线安装尚未验收。

## 数据库与启动

```bash
test -f .env || cp .env.example .env
# 编辑 .env，设置独立开发 PostgreSQL 和 Redis 的密码。
set -a
source .env
set +a
docker compose --env-file .env -f infrastructure/docker/compose.yml up -d postgres redis
./scripts/mvn.sh -B verify
```

也可以直接使用已有 PostgreSQL，配置 `POSTGRES_HOST`、`POSTGRES_PORT`、`POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD` 后复用已有数据库；同时需要可用 Redis，通过 `REDIS_ADDRESS`、`REDIS_PASSWORD`、`REDIS_DATABASE` 配置。使用已有 Redis 时可跳过 Compose 的 Redis 服务。受限网络可通过 `POSTGRES_IMAGE`、`REDIS_IMAGE` 指定可达镜像源。

分别在三个终端、从根目录启动：

```bash
# 终端1：导出本地配置，启动单实例 Runtime
set -a
source .env
set +a
./scripts/run-runtime.sh

# 终端2：导出 PostgreSQL/Redis 配置，启动 Java
set -a
source .env
set +a
./scripts/run-api.sh

# 终端3：前端
npm --prefix apps/web run dev
```

默认地址：API `127.0.0.1:8080`、Runtime `127.0.0.1:8000`、Web `127.0.0.1:5173`。健康检查为 Java `/actuator/health` 和 Runtime `/health`。

Runtime 默认保存到 `.data/runtime`，可设置 `RUNTIME_DATA_DIR`。同一目录仅允许一个 Runtime，必须一起保留执行去重记录与 Checkpoint。MinIO 为可选基础设施，启用 `storage` profile 前设置 `MINIO_ROOT_PASSWORD`；文件管理功能尚未实现。

## 任务 API

```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/tasks \
  -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: local-example' \
  -d '{"idempotency_key":"example-revision-1","input":{"message":"统计输入并独立核验","values":[2,4,6]},"limits":{"max_steps":20,"timeout_seconds":120}}'

# 使用响应中的 task_id 替换 TASK_ID
curl -sS http://127.0.0.1:8080/api/v1/tasks/TASK_ID
curl -sS 'http://127.0.0.1:8080/api/v1/tasks/TASK_ID/events?after=0'
curl -sS -X POST http://127.0.0.1:8080/api/v1/tasks/TASK_ID/cancel
```

同一幂等键和相同载荷返回同一 Task/Run，异载荷返回409。事件每页最多500条，以最后的 `sequence` 继续读取。取消按安全检查点和事务顺序处理，已经完成的任务保持终态。HTTP 超时表示结果未知，不能据此新建执行。

## 测试与契约

```bash
./scripts/test.sh

# PostgreSQL 集成测试需要独立数据库 eap_p0_test。
# 使用数据库管理工具提前执行 CREATE DATABASE eap_p0_test;
set -a
source .env
set +a
./scripts/test-integration.sh
```

基础验证默认不启用 PostgreSQL 集成和真实模型用例；集成脚本显式启用数据库测试，启动临时 Java/Python 服务与本地故障代理。测试数据使用随机请求键，不清空数据库；不要让其他调度器同时消费测试库。

```bash
# 导出边界契约
"${RUNTIME_PYTHON:-python}" scripts/export-contracts.py
# Java 格式化与检查
./scripts/mvn.sh spotless:apply
./scripts/mvn.sh spotless:check
```

真实模型配置放在本地环境中：`MODEL_PROVIDER=openai-compatible`、`MODEL_BASE_URL`、`MODEL_NAME`、`MODEL_API_KEY_ENV` 与相应密钥变量。单次模型调用时限由 `MODEL_TIMEOUT_SECONDS` 设置，与任务总时限分别生效。需要真实模型验证时显式执行：

```bash
RUN_LIVE_MODEL=true "${RUNTIME_PYTHON:-python}" -m pytest apps/agent-runtime/tests/test_live_model.py -q
RUN_LIVE_MODEL=true "${RUNTIME_PYTHON:-python}" -m pytest tests/integration/test_live_end_to_end.py -q
```

端到端真实模型测试使用独立的 `eap_p0_live_test`；配置的开发数据库账号需具备创建该测试库的权限。没有显式开启时不会调用外部模型。

架构与边界见[实现架构](docs/p0/architecture.md)、[已知限制](docs/p0/known-limitations.md)、[执行引擎决策](docs/adr/0001-p0-execution-and-frameworks.md)及[API说明](docs/api/v1.md)。

Java 持久化使用 MyBatis-Plus 3.5.17，普通操作走 BaseMapper，原子领取与租约等 SQL 位于 `apps/platform-api/src/main/resources/mapper/`。迁移决策与验证状态见 [ADR-0002](docs/adr/0002-mybatis-plus-persistence.md)；本次迁移测试尚未运行。

任务级分布式锁通过 `DistributedLock` 统一封装，当前实现为 Redisson Core 3.52.0。Java 需要连接 Redis；任务领取、取消、对账及失联处理使用同一任务键，锁只覆盖短数据库事务。`LOCK_WAIT_MS` 设置锁竞争等待，`LOCK_WATCHDOG_TIMEOUT_MS` 设置续期窗口，`LOCK_KEY_PREFIX` 隔离环境；同一数据库所有实例必须使用相同前缀。Redis 失联不降级为无锁执行，数据库保留 version CAS、幂等唯一约束和执行 fence。详细语义及未验证项见 [ADR-0003](docs/adr/0003-redisson-task-locks.md)。此次改动未运行验证。
