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
jangzang_docker         应用镜像构建、Compose 部署和宿主机 Nginx 配置
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

从源码离线构建需要提前准备 Wheelhouse、Maven 仓库缓存、npm 缓存和基础镜像。已有应用镜像时可直接加载部署。完整断网部署仍待验收。

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

## 使用 Docker Compose 部署 P0

进入 `jangzang_docker/`。如果尚无 `.env`，从 `.env.example` 复制一份，再填写用户名、密码和模型配置。使用已有 PostgreSQL 和 Redis 时，填写容器可直接访问的服务地址，并为平台准备专用数据库和可执行 Flyway 迁移的账号。`host.docker.internal` 是 Docker Desktop 上访问宿主机的默认示例，Linux 部署时请按实际网络填写地址。

已有三个镜像归档时可直接加载。仅有源码时先按本节末尾的命令构建镜像。

```bash
cd jangzang_docker
# AMD64 主机使用启用了 containerd 镜像存储的 Docker Engine，并改成 linux/amd64。
for component in frontend backend runtime; do
  docker load --platform linux/arm64 -i "${component}.tar"
done
```

根据基础设施选择其中一份 Compose 文件。

```bash
# 方案一：使用已有 PostgreSQL 和 Redis
docker compose -f docker-compose.yaml up -d --wait

# 方案二：Compose 同时运行 PostgreSQL 和 Redis，按需二选一
docker compose -f docker-compose.with-infra.yaml up -d --wait
```

每个归档都包含 Linux AMD64 和 ARM64。启用 containerd 镜像存储后也可以直接用 `docker load -i` 加载两种架构。在旧版 overlay2 镜像存储上，若目标架构无法从双架构归档加载，在启用了 containerd 的 Docker 上运行 `docker save --platform` 另存对应架构，或在目标机上从构建目录重新打包。

五容器方案使用 Compose 内部的 PostgreSQL 和 Redis，保留 `.env` 中的数据库名、用户名和密码，并将数据库和 Runtime 的状态写入各自的命名卷。离线使用五容器方案时，还需事先准备官方 `postgres:17-alpine` 和 `redis:7.4-alpine` 镜像。停机或切换方案时使用启动时对应的 Compose 文件执行 `down`，命名卷会保留。

将 `nginx-frontend.conf` 和 `nginx-backend.conf` 安装到宿主机 Nginx 在 `http` 块中加载的目录。替换两个示例域名、配置内网 DNS，需要时同步修改回环端口，然后执行 `nginx -t` 和 `nginx -s reload`。前台入口转发到 `127.0.0.1:18080`，并通过同源的 `/api/` 和 `/actuator/health` 访问后台。独立后台入口转发到 `127.0.0.1:18081`。前台容器通过 Docker DNS 解析 `backend`，Java 通过 Docker DNS 解析 `runtime`。Runtime 只有单实例，内部 API 不发布到宿主机。两个入口仅用于受控网络内的 P0 验证。

Git 仓库保留构建定义和配置模板。获取源码后，从仓库根目录准备三个构建目录的资源。

```bash
./scripts/mvn.sh -B package
npm --prefix apps/web ci --no-audit --no-fund
npm --prefix apps/web run build

cp apps/platform-api/target/platform-api-0.1.0.jar jangzang_docker/backend/platform-api.jar
mkdir -p jangzang_docker/frontend/dist jangzang_docker/runtime/src/agent_runtime
cp -R apps/web/dist/. jangzang_docker/frontend/dist/
cp apps/agent-runtime/requirements.lock jangzang_docker/runtime/
cp apps/agent-runtime/src/agent_runtime/*.py jangzang_docker/runtime/src/agent_runtime/
```

进入 `jangzang_docker/` 后可以分别构建三个镜像。重建需要访问官方基础镜像和 PyPI，或使用本地缓存或镜像源。

```bash
(cd frontend && docker build -t jangzang/frontend:0.1.0 .)
(cd backend  && docker build -t jangzang/backend:0.1.0 .)
(cd runtime  && docker build -t jangzang/runtime:0.1.0 .)
```

单机 `docker build` 默认构建当前架构。若需重制双架构归档，使用支持双架构构建的 Buildx 和启用 containerd 镜像存储的 Docker Engine，在构建命令中指定 `--platform linux/amd64,linux/arm64 --provenance=false`，再执行 `docker save -o <组件>.tar jangzang/<组件>:0.1.0`。

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

# 集成测试需要仓库根目录的 .env、专用数据库 eap_p0_test 和可用的测试 Redis。
# 使用数据库管理工具提前执行 CREATE DATABASE eap_p0_test;
set -a
source .env
set +a
./scripts/test-integration.sh
```

基础验证默认跳过数据库集成和真实模型用例。集成脚本使用 `POSTGRES_*` 和 `REDIS_*` 配置，启用 Java 数据库测试，并通过临时 Java/Python 服务和本地代理运行跨进程故障测试。测试数据使用随机请求键，测试时让独立调度器专用 `eap_p0_test`。

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

Java 使用 MyBatis-Plus 读写 PostgreSQL，原子领取与租约 SQL 位于 `apps/platform-api/src/main/resources/mapper/`。Redis 为任务级变更提供短事务锁，PostgreSQL 以 version CAS、唯一约束和执行 fence 保护持久化状态。使用同一任务数据库的所有 Java 实例须配置相同的 `LOCK_KEY_PREFIX`。

详细说明见 [P0 实现架构](docs/p0/architecture.md)、[已知限制](docs/p0/known-limitations.md)、[v1 API](docs/api/v1.md)、[持久化 ADR](docs/adr/0002-mybatis-plus-persistence.md)和[任务锁 ADR](docs/adr/0003-redisson-task-locks.md)。
