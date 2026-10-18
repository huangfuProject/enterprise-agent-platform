package com.enterprise.platform.application.locking;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 分布式锁统一入口，业务方只提供资源键、有限等待时间和临界区。
 *
 * <p>临界区同步执行；若包含数据库事务，必须在回调返回前完成提交或回滚。
 * 不支持把锁交给异步线程释放，也不把 Redis 锁当作数据库条件更新的替代品。
 *
 * @author chomy
 **/
public interface DistributedLock {
  /**
   * 获取资源锁后执行回调；竞争超时或回调返回 null 时返回空，基础设施异常显式抛出。
   *
   * @param key 不含环境前缀的业务资源键
   * @param waitTime 锁竞争的最长等待时间，零表示不等待；Redis 通信另受客户端超时约束
   * @param action 同步临界区，不能将事务提交延迟到回调之外
   **/
  <T> Optional<T> tryExecute(String key, Duration waitTime, Supplier<T> action);
}
