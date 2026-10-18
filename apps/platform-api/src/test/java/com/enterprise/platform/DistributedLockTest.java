package com.enterprise.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.enterprise.platform.application.locking.DistributedLockException;
import com.enterprise.platform.infrastructure.locking.RedissonDistributedLock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

/**
 * 分布式锁适配器单元测试，使用客户端 Mock 验证边界，不宣称验证真实 Redis 互斥。
 *
 * @author chomy
 **/
class DistributedLockTest {
  private RedissonClient client;
  private RLock lock;
  private RedissonDistributedLock locks;

  /**
   * 每个测试使用独立 Mock，不访问服务器。
   **/
  @BeforeEach
  void setUp() {
    client = mock(RedissonClient.class);
    lock = mock(RLock.class);
    when(client.getLock("test:task:one")).thenReturn(lock);
    locks = new RedissonDistributedLock(client, "test:");
  }

  /**
   * 回调完成后才解锁，确保调用方能把完整事务放在回调中。
   **/
  @Test
  void unlocksAfterActionCompletes() throws Exception {
    when(lock.tryLock(100, TimeUnit.MILLISECONDS)).thenReturn(true);
    var result =
        locks.tryExecute(
            "task:one",
            Duration.ofMillis(100),
            () -> {
              verify(lock, never()).unlock();
              return "committed";
            });
    assertThat(result).contains("committed");
    verify(lock).unlock();
  }

  /**
   * 未取得锁时不执行临界区，也不释放别人的锁。
   **/
  @Test
  void contentionSkipsAction() throws Exception {
    when(lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(false);
    var result =
        locks.tryExecute(
            "task:one",
            Duration.ZERO,
            () -> {
              throw new AssertionError("action must not run");
            });
    assertThat(result).isEmpty();
    verify(lock, never()).unlock();
  }

  /**
   * 业务异常原样传播，finally 仍释放当前线程持有的锁。
   **/
  @Test
  void failureStillUnlocks() throws Exception {
    when(lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(true);
    var failure = new IllegalStateException("rollback");
    assertThatThrownBy(
            () ->
                locks.tryExecute(
                    "task:one",
                    Duration.ZERO,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);
    verify(lock).unlock();
  }

  /**
   * Redis 不可用必须失败关闭，不能降级成无锁执行。
   **/
  @Test
  void redisFailureIsExplicit() throws Exception {
    when(lock.tryLock(0, TimeUnit.MILLISECONDS)).thenThrow(new RedisException("unavailable"));
    assertThatThrownBy(
            () ->
                locks.tryExecute(
                    "task:one",
                    Duration.ZERO,
                    () -> {
                      throw new AssertionError("action must not run");
                    }))
        .isInstanceOf(DistributedLockException.class)
        .hasMessage("LOCK_UNAVAILABLE");
    verify(lock, never()).unlock();
  }

  /**
   * 等待被中断后恢复线程标记，不把服务关闭信号吞掉。
   **/
  @Test
  void interruptionIsPreserved() throws Exception {
    when(lock.tryLock(0, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException());
    try {
      assertThatThrownBy(() -> locks.tryExecute("task:one", Duration.ZERO, () -> "unused"))
          .isInstanceOf(DistributedLockException.class)
          .hasMessage("LOCK_INTERRUPTED");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    verify(lock, never()).unlock();
  }

  /**
   * 已完成的业务不会因解锁时连接异常被报告成事务回滚。
   **/
  @Test
  void releaseFailureDoesNotHideCommittedResult() throws Exception {
    when(lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(true);
    doThrow(new RedisException("lost connection")).when(lock).unlock();
    assertThat(locks.tryExecute("task:one", Duration.ZERO, () -> "committed"))
        .contains("committed");
  }
}
