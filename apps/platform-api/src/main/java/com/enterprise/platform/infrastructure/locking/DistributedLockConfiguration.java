package com.enterprise.platform.infrastructure.locking;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 单节点 Redis 开发配置；只使用 Redisson Core，避免无关 Spring Data Redis 适配依赖。
 *
 * @author chomy
 **/
@Configuration
public class DistributedLockConfiguration {
  /**
   * 密码由环境注入，连接与命令超时有界；Redis 不可用时不退化为进程内锁。
   **/
  @Bean(destroyMethod = "shutdown")
  public RedissonClient redissonClient(
      @Value("${p0.lock.address}") String address,
      @Value("${p0.lock.password}") String password,
      @Value("${p0.lock.database}") int database,
      @Value("${p0.lock.watchdog-timeout-ms}") long watchdogTimeout) {
    if (watchdogTimeout < 1000) throw new IllegalArgumentException("INVALID_LOCK_WATCHDOG_TIMEOUT");
    var config = new Config();
    config.setLockWatchdogTimeout(watchdogTimeout);
    var server =
        config
            .useSingleServer()
            .setAddress(address)
            .setDatabase(database)
            .setConnectTimeout(2000)
            .setTimeout(2000)
            .setRetryAttempts(0);
    if (password != null && !password.isBlank()) server.setPassword(password);
    return Redisson.create(config);
  }
}
