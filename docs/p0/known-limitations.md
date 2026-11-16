# 已知限制

1. **受控开发环境**：没有认证、租户/用户权限、内部服务身份或防重放签名。固定 development tenant 只是持久化占位，不能接入真实敏感数据。API 与 Runtime 默认绑定回环；数据库访问范围应由部署网络单独限制。
2. **真实模型验证范围有限**：已验证 qwen3.7-flash-2026-07-15 在示例统计任务中的工具调用、SubAgent 和 ToolStrategy 结构化结果。默认 Fake 用于稳定回归；小样例通过不代表所有模型或复杂任务的质量、可靠性和容量已验收。
3. **恢复有限**：SQLite Saver 的独立 interrupt 场景跨连接恢复通过；Main Agent Checkpoint 保存通过。没有将整个业务 Task 暂停/恢复 API、人工审批、自动续跑或生产副作用恢复作为已实现能力。
4. **未知执行不自动重放**：租约过期/Runtime 重启转失败并保留 Checkpoint。若旧 Worker 已完成只读计算，平台可能仍按失联失败；需人工核对。fencing 防止旧结果覆盖业务终态，不保证停止所有外部副作用。无 exactly-once 承诺。
5. **单 Runtime**：目录锁强制单实例；最多四个独立执行进程，不支持分布式共享 SQLite。单 Java 调度线程逐个轮询，慢连接会影响后续任务。默认四任务、2 秒 HTTP 时限与15秒租约只适合 PoC，需要按负载评估。
6. **事件与时间**：部署时必须同步各节点时钟；任务事件仍以 sequence 为排序依据。Checkpoint/Runtime 元数据/PostgreSQL 不具备跨库原子提交。没有生产消息 Outbox、SSE、事件归档或分页保留策略。
7. **版本敏感**：DeepAgents 默认文件工具的同名中间件替换、原生 task 上下文行为只对锁定版本有证据，升级必须回归。工具白名单不是生产授权引擎；无任意文件、网络、Shell 或代码执行能力。
8. **输出与子 Agent**：真实 Provider 使用 ToolStrategy(AgentResult) 约束最终响应并以 Pydantic 校验；没有验证供应商原生 JSON Schema 解码模式。只允许一个固定模板、一层一次委派，子失败可降级，不支持动态任意角色、多子 Agent 并行或跨用户共享。
9. **基础设施**：Java 需要 PostgreSQL 和 Redis。三容器和五容器 Compose 已在 Mac Docker Desktop 验证，AMD64 应用闭环在模拟环境验证。MinIO 仍只预留可选基础设施，Artifact 尚未接入。P0 前端为连接检查页，任务状态可通过 API 查询。
10. **离线与生产部署**：三个应用有 AMD64 和 ARM64 的 `docker save` 归档，五容器方案离线部署还需 PostgreSQL 和 Redis 基础设施镜像。实体 AMD64 主机、完整断网部署、Helm、SBOM、漏洞扫描、备份恢复与容量仍待验证。
11. **内部错误格式**：Python 参数错误使用422，与 Java 公共错误 DTO 不完全一致；运行失败当前归为 AGENT_FAILED，详细模型/策略错误未细分。不会把可能包含凭证的供应商异常返回给 API。

## Redis 任务协调的验证边界

在独立 PostgreSQL 与 Redis 上，Java 数据库回归和 Fake 跨进程故障测试已通过。跨 Java 实例互斥、Redis 断连与故障切换、watchdog 过期及长暂停后的锁行为仍待专门测试。候选扫描可能受锁竞争影响，领取顺序不保证严格 FIFO。数据库 CAS 和执行 fence 约束陈旧写入，副作用去重仍需独立机制。见 [ADR-0003](../adr/0003-redisson-task-locks.md)。

## CI 的基础设施缺口

当前 [GitHub Actions 流程](../../.github/workflows/ci.yml)只声明了 PostgreSQL 服务，Java 集成测试还需要 Redis。该缺口影响 CI 的集成步骤。上述验证结果来自本地隔离环境。
