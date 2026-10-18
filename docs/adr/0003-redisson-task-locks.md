# ADR-0003：统一分布式锁与任务并发控制

日期：2026-10-18。状态：按维护者要求实施，编译及运行验证未运行。

## 决定

维护者明确要求将显式数据库行锁替换为 Redisson，并抽象可复用锁工具。新增 `DistributedLock` 接口及 `RedissonDistributedLock`，采用 Redisson Core 3.52.0，不引入无关的 Spring Data Redis 集成。

官方参考：[Redisson 锁与同步器](https://redisson.pro/docs/data-and-services/locks-and-synchronizers/index.html)、[配置](https://redisson.pro/docs/configuration/)、[3.52.0 发布记录](https://github.com/redisson/redisson/blob/master/CHANGELOG.md)。选定版本已固定，但本次未执行依赖兼容性验证。

Redis 现在是 Java 任务协调的必需依赖。此决定替代 ADR-0001 中“不引入 Redis”的选择，以及 ADR-0002 的显式行锁与非 CAS version 策略；Java/Python 边界、PostgreSQL 持久化与 Flyway 保持。

## 锁工具契约

```java
locks.tryExecute("task:" + taskId, waitTime,
    () -> transactionTemplate.execute(status -> mutateTask()));
```

- 资源键统一添加环境前缀；同一任务数据库的所有实例必须使用相同前缀。
- 有限等待，竞争未获锁返回空；Redis 错误/中断失败关闭，不降级为本地锁或无锁执行。
- 使用不指定 leaseTime 的 tryLock 重载，由 watchdog 续期。正常路径由持有锁的同一线程 unlock，不使用 forceUnlock。
- 严格顺序：获取 Redis 锁 → 数据库事务 → 提交/回滚 → 解锁。Repository 禁止这些写入口加入已有外层事务，避免提前释放锁。
- 事务返回后若解锁失败，记录安全日志并等待锁到期；数据库可能已提交，不把结果谎报成回滚。
- 锁不跨 HTTP/模型调用，不在整个 Agent 执行期间持有。

## 数据库条件更新仍是安全边界

Redis 锁不保证在故障切换、长 GC、网络分区下永远有效，因此不能仅删除 `FOR UPDATE`。

- Task 快照增加内部 `version`，更新使用 `WHERE task_id=? AND version=? AND state=?`；影响行数非1时事务回滚。
- version 现在是每次受控写事务递增的乐观并发版本；同状态续租/事件导入也递增，保护游标与事件序号，不使用 MP 乐观锁插件。
- CAS 必须先于事件追加。状态未改变时不追加状态事件、不改业务 updated_at。
- Run 回写再次检查 owner/fence/有效租约，失联更新再次检查租约确已过期。
- 唯一幂等键、事务与事件复合主键继续保留；这些约束不能被 Redis 锁取代。

普通 INSERT/UPDATE 本身仍会产生 PostgreSQL 内部锁。本次移除的是显式 `SELECT ... FOR UPDATE [SKIP LOCKED]`，不宣称数据库完全无锁。

## 调度

普通 SELECT 有界读取最早32个排队候选，逐一非阻塞竞争任务键，锁内重新检查 QUEUED 并执行 CAS。每轮最多领取一个。过期扫描每轮最多100个候选，逐项拿锁及事务；已经续租的候选回滚并跳过。

取消与对账使用相同任务键。取消锁竞争超时返回409/TASK_BUSY；Redis 不可用或等待中断返回503/LOCK_UNAVAILABLE、LOCK_INTERRUPTED；CAS 冲突为409/TASK_CONCURRENT_UPDATE。后台未获取锁则下一轮再尝试，不代表任务执行失败。

## 取舍与待验证

增加 Redis 的部署、可用性和网络成本；候选扫描存在竞争及暂时饥饿，不承诺严格 FIFO。watchdog、PostgreSQL Worker 租约和 fence 是三种不同机制，不能互相替代，不提供 exactly-once。

服务关闭时数据库可保留任务，但 Java 启动和任务协调需要可用 Redis。单节点 Compose 仅供开发；不宣称已验证生产 Redis 高可用或故障切换安全。

维护者说明服务器关机且无需验证，本次不运行编译、单元、数据库或跨进程测试。新增 Mock 单元测试覆盖竞争、异常释放、Redis失败、中断及解锁不确定结果；数据库测试新增陈旧版本拒写，均未运行。服务恢复后需验证跨 Java 实例互斥、锁失效与 CAS、watchdog、Redis断连、事务回滚、租约及取消竞态。
