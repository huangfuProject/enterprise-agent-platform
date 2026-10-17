# ADR-0001：P0 框架与执行边界

日期：2026-10-17。状态：P0 已实现并测试，生产适用性未验证。

## 背景与决定

保持 Java 21/Spring Boot 3.x、Python 3.12、Vue 3、PostgreSQL 基线。Java 是业务任务状态的唯一权威；Python 是执行快照、Agent 状态和 Checkpoint 的拥有者。HTTP JSON v1 + 拉取事件，不引入 Redis、Temporal 或消息中间件。

DeepAgents 原生 Main Agent/tool loop/`task` 委派负责智能执行；其产物已经是 LangGraph 图，不再为“组合框架”额外套一层无意义的图。LangGraph SQLite Saver 保存 Main Agent 图状态。另建一个独立、明确的审批图，以 interrupt/Command(resume) 验证恢复语义。

自有 Harness 只封装：模型适配、固定子 Agent 模板、参数校验、共享步骤预算、一层一次委派、工具白名单、取消和结构化输出校验。子 Agent 只有统计工具；委派描述由确定性代码替换为数值投影，不复制主对话。原生子 Agent 能满足本次 PoC；复杂固定流程才使用自定义子图。子图的输入/输出映射更明确，但需要自己编写委派与生命周期管理，不是天然安全边界。

实测 DeepAgents 0.7.28 要求 `FilesystemMiddleware(tools=[])` 必须包含 read_file；因此本版本采用同名无工具中间件替换默认文件中间件。未提供宿主文件 backend，额外以每次调用白名单阻止文件/网络/Shell 能力。此替换方式对框架版本敏感，升级必须重跑工具与隔离测试。默认 general-purpose 子 Agent 即使存在，调用也被委派白名单拒绝。

## 存储与故障策略

- PostgreSQL 保存 Task、单次 Run、事件；Flyway 管理迁移。
- `FOR UPDATE SKIP LOCKED` 领取、数据库时间计算租约、fence 防旧结果覆盖；事务外调用 Runtime。
- 失联的执行转 FAILED/WORKER_LOST（取消请求则 CANCELLED）；P0 不自动重试未知执行。人工核对后只能创建新的任务键。
- Runtime 单实例拥有一个目录；每执行一个子进程，有限并发 4，超时/取消可以终止该进程。父进程死亡后子进程自检退出；目录锁防多实例误判恢复。
- SQLite 分别保存 Runtime 去重/事件和 LangGraph Checkpoint；简单、可跨连接恢复，适合本地单 Runtime PoC。不能共享网络文件系统或据此承诺多 Runtime 高可用。Checkpoint 与业务数据库无分布式事务。
- Runtime 重启将未知执行置失败，不自动重放。读取 Checkpoint 不代表安全重放外部副作用。

投递、领取、模型调用、工具副作用和最终状态具有不同语义。本方案不提供 exactly-once；只验证持久化记录不重复创建、固定 execution_id 去重与终态写入 fencing。两个工具均无外部副作用。

## 模型与后续

默认确定性模型产生 tool_call，由真实 ToolNode 调度；便于离线和重复测试。真实接入仅集中在 OpenAI 兼容适配器，设置 timeout/max_retries=0；不绑定供应商私有业务 API。Fake 通过不代表真实模型具备工具调用/结构化输出能力。实测 qwen3.7-flash-2026-07-15：纯文本 JSON 会被包进 Markdown 代码块，因此真实 Provider 改用 ToolStrategy(AgentResult)，避免靠宽松文本解析容错；最终仍经 Pydantic 校验。只有该模型与示例的调用能力已有证据，不外推其他模型。

P1 先完成身份与授权，再接入真实敏感资源。暂停、人审业务 API、任务修订和自动副作用恢复未在 P0 开放。
