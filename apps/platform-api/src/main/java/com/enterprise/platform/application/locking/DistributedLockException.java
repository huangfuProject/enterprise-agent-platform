package com.enterprise.platform.application.locking;

/**
 * 分布式锁的安全错误边界；不把 Redis 地址、命令参数或凭证返回给调用方。
 *
 * @author chomy
 **/
public class DistributedLockException extends RuntimeException {
  /**
   * 保存统一错误码与诊断原因，HTTP 层只公开错误码。
   **/
  public DistributedLockException(String code, Throwable cause) {
    super(code, cause);
  }
}
