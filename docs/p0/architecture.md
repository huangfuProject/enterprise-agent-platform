# P0 实际架构

```mermaid
flowchart LR
  Web[Vue 最小连接检查页] --> Java[Java API / TaskService]
  Java --> PG[(PostgreSQL Task / Run / Event)]
  Dispatcher[Java Dispatcher / 租约与 fencing] --> PG
  Dispatcher --> HTTP[TaskExecutionEngine HTTP v1]
  HTTP --> Runtime[FastAPI 单 Runtime]
  Runtime --> Store[(SQLite 执行去重 / 快照 / 事件)]
  Runtime --> Process[有界独立执行进程]
  Process --> DeepAgents[DeepAgents Main / task SubAgent]
  DeepAgents --> Tools[两个受控工具]
  DeepAgents --> Model[模型适配 / 默认 Fake]
  DeepAgents --> Saver[(LangGraph SQLite Checkpoint)]
```

Java 分为 api/application/domain/infrastructure。Controller 处理 DTO 与投影，Service 处理幂等载荷哈希，Repository 负责状态转换事务与数据库锁，MyBatis-Plus Mapper 承担 SQL 执行和实体映射，Dispatcher 只做有限轮询/失联检测，HttpTaskExecutionEngine 位于扩展接口后。

API 新任务为 QUEUED，原子领取后 CLAIMED；收到 Runtime RUNNING 才进入 RUNNING。Run 的 CREATED/CLAIMED/ACCEPTED/RUNNING 与 Task 状态分开保存；Checkpoint ID 仅在最终图快照可读后写入 Run。所有状态更新带版本，敏感的领取/终态更新由任务级 Redisson 锁协调，version CAS 拒绝陈旧写入；fence 校验绑定持有者与有效租约。数据库不可用时 API 返回 503，调度器下轮尝试；不在事务中做 HTTP/模型调用。

Runtime 提交先持久化并去重，随后启动进程；工具/子 Agent 事件追加持久化。执行进程退出由监控线程识别，服务进程退出由 Java 租约识别。取消标记可以早于提交持久化，避免迟到提交启动已取消任务。终态事件和 Runtime 最终快照原子写入；Java 拉取事件后按运行内序号去重并分配业务序号。

映射关系：Task UUID 表示业务任务；P0 revision 固定 1；execution_id UUID 表示唯一执行尝试/AgentRun；`checkpoint_thread_id = task_id + '-' + execution_id`；LangGraph state 是内部消息与工具状态；Checkpoint ID 是该 thread 的版本化状态点，一个 thread 有多个 Checkpoint。业务 Task 不把 Checkpoint 当作完成标志。请求还有稳定 request_id、trace_id，事件带 task/execution/trace/sequence/time。

工具使用 Pydantic 输入/输出 Schema；有限上下文工具只返回任务/运行 ID 和用途，统计工具只计算最多 100 个有限数值。真实 Provider 经 ToolStrategy(AgentResult) 返回结构化响应，Fake 保留确定性 JSON；模型输出均经 AgentResult 校验，不能作为权限结论。SubAgent 不自动获得主工具集合，不继承主消息，委派描述由数值投影重建。P0 不访问真实文件或数据源，没有完整租户权限系统。

事件 API 是轮询，不提供 SSE。MinIO 仅可选环境预留，未接入 Artifact；Redis 仅用于分布式锁；Temporal、SQL/RAG/RBAC/SkillHub 未实现。

## Java 持久化实现

- 使用 MyBatis-Plus 3.5.17 Spring Boot 3 Starter，持久化实体与 API DTO/业务快照分离。
- TaskMapper/RunMapper 继承 BaseMapper，EventMapper 处理复合主键事件；并发敏感 SQL 在 XML，JSONB 由显式 TypeHandler 映射。
- Repository 保留 TransactionTemplate 与状态机，SQL 从 Java 业务方法移出；不改变表结构、HTTP 契约和故障策略。
- 本次迁移按维护者要求未运行验证；此前测试证据属于迁移前版本，参见 ADR-0002。

## 分布式锁与乐观并发

`DistributedLock` 封装 Redisson，业务代码不依赖 RLock。普通候选查询后按 task_id 竞争锁，不再使用显式 FOR UPDATE；锁在事务提交/回滚后释放，不覆盖 HTTP 或模型执行。数据库条件更新、唯一约束、事件事务、Worker 租约与 fence 仍保留。

TaskRecord 新增内部 version，数据库已有 version 列改为真正的 CAS 版本；每次受控写事务递增，状态不变时不改业务 updated_at。无需 Schema 迁移，对外 TaskView 与跨语言契约保持。

Redis 现为 Java 的必需依赖，Compose 增加仅回环暴露的开发 Redis；密码由本地环境注入。锁竞争、超时与失败语义见 ADR-0003。本次编译、测试及服务器部署未运行。
