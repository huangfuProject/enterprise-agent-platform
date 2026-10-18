package com.enterprise.platform.domain;

import java.util.Set;

/**
 * 业务任务状态机；Runtime ACCEPTED 与 Checkpoint 已保存均不是独立的任务终态。
 *
 * <p>Repository 在任务分布式锁及版本 CAS 保护下检查迁移。终态不可逆，取消只能承诺协作请求的生效顺序。
 *
 * @author chomy
 **/
public enum TaskState {
  /**
   * 已创建并持久化，尚未被调度器领取。
   **/
  QUEUED,
  /**
   * 已领取并建立租约，不代表 Python 已开始。
   **/
  CLAIMED,
  /**
   * 已观察到 Runtime 开始执行。
   **/
  RUNNING,
  /**
   * 取消已在平台记录，等待执行侧确认或租约过期。
   **/
  CANCEL_REQUESTED,
  /**
   * 平台接受了有效执行的成功结果。
   **/
  SUCCEEDED,
  /**
   * 执行失败或失联，未知执行不会自动重放。
   **/
  FAILED,
  /**
   * 取消最终生效，不再接受迟到成功覆盖。
   **/
  CANCELLED;

  /**
   * 判断是否为不可再迁移的终态；重复请求只能读取既有结果。
   **/
  public boolean terminal() {
    return Set.of(SUCCEEDED, FAILED, CANCELLED).contains(this);
  }

  /**
   * 检查下一业务状态是否合法；执行方已完成但平台尚未观察 RUNNING 时可直接接收成功。
   **/
  public boolean allows(TaskState target) {
    return switch (this) {
      case QUEUED -> Set.of(CLAIMED, CANCELLED).contains(target);
      case CLAIMED -> Set.of(RUNNING, CANCEL_REQUESTED, SUCCEEDED, FAILED).contains(target);
      case RUNNING -> Set.of(CANCEL_REQUESTED, SUCCEEDED, FAILED).contains(target);
      case CANCEL_REQUESTED -> Set.of(CANCELLED, FAILED).contains(target);
      default -> false;
    };
  }
}
