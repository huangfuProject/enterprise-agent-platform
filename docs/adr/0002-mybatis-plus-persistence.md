# ADR-0002：Java 持久化层采用 MyBatis-Plus

日期：2026-10-18。状态：已实施代码迁移；本次验证未运行，待数据库服务器恢复后回归。

> 后续变更：ADR-0003 将显式行锁替换为统一 Redisson 分布式锁，并将 version 改为 CAS 并发版本；原 SQL 行锁方案不再是当前实现。两次迁移均待运行验证。

## 背景与决定

按项目维护者要求，将 Java 的 JdbcTemplate 持久化访问替换为 MyBatis-Plus。选择 `mybatis-plus-spring-boot3-starter:3.5.17`，沿用 Spring Boot 3.5.7、Java 21、PostgreSQL 与 Flyway，不另行混用 MyBatis Starter。版本及集成方式参考[官方安装说明](https://baomidou.com/getting-started/install/)。

- `TaskRepository` 保留状态机校验、Task/Run/事件事务以及 Runtime 对账业务。
- `infrastructure.persistence` 放置表实体、Mapper、事件查询投影及 JSONB 类型处理器，实体不作为 API DTO。
- 普通单表操作使用 `BaseMapper` 和 Lambda Wrapper；任务/运行联合快照与并发敏感语句放在 `resources/mapper/*.xml`。
- `EventMapper` 显式处理 `(task_id, sequence)` 复合主键，不为使用 BaseMapper 虚构单列 ID。
- 不引入通用 `IService` 层，避免与已有应用服务和事务边界重复。

## 必须保持的行为

- 唯一幂等键 + `ON CONFLICT DO NOTHING` 创建任务，同键异载荷仍返回409。
- 同一事务创建 Task、Run 和首个事件；数据库约束与默认值保持不变。
- `FOR UPDATE SKIP LOCKED` 原子领取；取消、对账和租约过期仍在 Task 行锁下串行化。
- 数据库时钟计算租约，fence 防止失效持有者回写；不宣称 exactly-once。
- HTTP/模型调用始终在数据库事务外。
- JSONB 显式映射为 JsonNode；nullable 结果、错误、Checkpoint 引用必须能被写成 SQL NULL，不受 MyBatis-Plus 默认非空更新策略影响。
- MyBatis 本地缓存设为 STATEMENT，关闭二级缓存，避免复用锁前快照。
- Task 状态变更的 version 仍仅记录变更次数，不添加 `@Version` 或改变为乐观锁协议。

## 验证及限制

服务器已关机，维护者明确要求本次无需验证。本次不运行单元、数据库或跨进程测试，不将历史 JdbcTemplate 测试结果当作新实现证据；当前迁移不宣称已验证可运行。

已迁移数据库故障注入为仅存在于测试 classpath 的 Mapper，保留原数据库并发领取、失联 fencing、取消竞态测试；补充并发重复创建以及 JSONB/时间/NULL/联合快照映射测试，均待运行。

服务器恢复后执行现有回归入口 `./scripts/test-integration.sh`，并检查映射加载、事务回滚、幂等、事件、租约和取消竞态。离线交付需补充此次新增的 Maven 依赖缓存。
