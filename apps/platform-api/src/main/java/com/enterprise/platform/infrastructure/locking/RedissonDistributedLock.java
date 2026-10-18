package com.enterprise.platform.infrastructure.locking;

import com.enterprise.platform.application.locking.DistributedLock;
import com.enterprise.platform.application.locking.DistributedLockException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Redisson 分布式锁实现：有限等待、watchdog 续期、同线程释放。
 *
 * <p>不传固定 leaseTime，让 watchdog 在短数据库临界区内续期；JVM 长暂停或 Redis 故障
 * 仍可能使锁失效，持久化层必须使用 version/fence 条件更新防止陈旧回写。
 *
 * @author chomy
 **/
@Component
public class RedissonDistributedLock implements DistributedLock {
  private static final Logger LOG = LoggerFactory.getLogger(RedissonDistributedLock.class);
  private final RedissonClient client;
  private final String prefix;

  /**
   * 统一拼接环境命名空间；同一数据库的所有 Java 实例必须使用相同前缀。
   **/
  public RedissonDistributedLock(
      RedissonClient client, @Value("${p0.lock.key-prefix}") String prefix) {
    this.client = client;
    if (prefix == null || prefix.isBlank())
      throw new IllegalArgumentException("LOCK_PREFIX_REQUIRED");
    this.prefix = prefix;
  }

  /**
   * 竞争失败不执行回调；业务异常原样传播，Redis 异常映射为统一安全错误码。
   **/
  @Override
  public <T> Optional<T> tryExecute(String key, Duration waitTime, Supplier<T> action) {
    Objects.requireNonNull(waitTime);
    Objects.requireNonNull(action);
    if (key == null || key.isBlank() || key.length() > 240 || waitTime.isNegative())
      throw new IllegalArgumentException("INVALID_LOCK_ARGUMENT");
    RLock lock = client.getLock(prefix + key);
    try {
      // 此重载不指定 leaseTime，使用 Redisson watchdog 管理续期。
      if (!lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS)) return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DistributedLockException("LOCK_INTERRUPTED", e);
    } catch (RedisException e) {
      throw new DistributedLockException("LOCK_UNAVAILABLE", e);
    }
    try {
      return Optional.ofNullable(action.get());
    } finally {
      try {
        // unlock 自身验证线程所有权，不能 forceUnlock，也不能删除其他实例的锁。
        lock.unlock();
      } catch (IllegalMonitorStateException | RedisException e) {
        // 此处数据库可能已提交，不能把解锁失败伪装成事务回滚；等待锁到期并保留诊断。
        LOG.warn("event=distributed_lock_release_failed code=LOCK_RELEASE_UNCERTAIN");
      }
    }
  }
}
