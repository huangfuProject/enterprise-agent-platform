# ADR-0002：Java 持久化层采用 MyBatis-Plus

记录日期为 2026-10-18。MyBatis-Plus 持久化方案已实施，后续本地数据库与跨进程回归已通过。

> [ADR-0003](0003-redisson-task-locks.md)更新了锁和 version 策略。以下原始行为要求保留当时采用显式行锁的背景。

## 背景与决定

按项目维护者要求，将 Java 的 JdbcTemplate 持久化访问替换为 MyBatis-Plus。选择 `mybatis-plus-spring-boot3-starter:3.5.17`，沿用 Spring Boot 3.5.7、Java 21、PostgreSQL 与 Flyway，不另行混用 MyBatis Starter。版本及集成方式参考[官方安装说明](https://baomidou.com/getting-started/install/)。

- `TaskRepository` 保留状态机校验、Task/Run/事件事务以及 Runtime 对账业务。
- `infrastructure.persistence` 放置表实体、Mapper、事件查询投影及 JSONB 类型处理器，实体不作为 API DTO。
- 普通单表操作使用 `BaseMapper` 和 Lambda Wrapper；任务/运行联合快照与并发敏感语句放在 `resources/mapper/*.xml`。
- `EventMapper` 显式处理 `(task_id, sequence)` 复合主键，不为使用 BaseMapper 虚构单列 ID。
- 不引入通用 `IService` 层，避免与已有应用服务和事务边界重复。

## 迁移时的行为要求

- 唯一幂等键 + `ON CONFLICT DO NOTHING` 创建任务，同键异载荷仍返回409。
- 同一事务创建 Task、Run 和首个事件；数据库约束与默认值保持不变。
- 当时由 `FOR UPDATE SKIP LOCKED` 领取，并在 Task 行锁下协调取消、对账和租约过期。ADR-0003 更新了这些实现。
- 数据库时钟计算租约，fence 防止失效持有者回写；不宣称 exactly-once。
- HTTP/模型调用始终在数据库事务外。
- JSONB 显式映射为 JsonNode；nullable 结果、错误、Checkpoint 引用必须能被写成 SQL NULL，不受 MyBatis-Plus 默认非空更新策略影响。
- MyBatis 本地缓存设为 STATEMENT，关闭二级缓存，避免复用锁前快照。
- 当时 version 仅记录变更次数。现行自定义 SQL 使用 version CAS，仍由 Mapper 明确管理。

## 验证及限制

迁移时未运行测试。2026-11-16 在 MyBatis-Plus 与 Redisson 合并后的实现上执行 `./scripts/test-integration.sh`，Java 16 项与 Fake 跨进程 7 项通过。涵盖并发重复创建、JSONB/时间/NULL 快照映射、并发领取、失联 fencing 和取消竞态。

从源码进行离线构建需要预先准备 MyBatis-Plus 及其 Maven 依赖。部署与恢复的验证边界见 [P0 已知限制](../p0/known-limitations.md)。
