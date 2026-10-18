package com.enterprise.platform.application;

import com.enterprise.platform.domain.*;
import com.enterprise.platform.infrastructure.TaskRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 基于 PostgreSQL 的有限并发调度与 Runtime 对账。
 *
 * <p>数据库领取/回写与外部 HTTP 分离；失联按租约失败关闭，不自动重放未知副作用。
 *
 * @author chomy
 **/
@Component
@ConditionalOnProperty(name = "p0.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class DatabaseTaskDispatcher {
  private final TaskRepository repository;
  private final TaskExecutionEngine engine;
  // 进程重启生成新身份，避免新实例误认上一实例的租约仍归自己所有。
  private final String owner = UUID.randomUUID().toString();
  private final Logger log = LoggerFactory.getLogger(getClass());

  /**
   * 注入执行适配器；每个调度器实例拥有独立租约 owner，重启不会复用旧身份。
   **/
  public DatabaseTaskDispatcher(TaskRepository repository, TaskExecutionEngine engine) {
    this.repository = repository;
    this.engine = engine;
  }

  /**
   * 运行一轮有界调度。
   *
   * <p>先回收过期租约，再领取任务并查询 Runtime。单个任务通信异常不阻塞其他任务的 后续对账；续租仅在成功取得快照和事件、并通过持有者校验后发生。
   **/
  @Scheduled(fixedDelayString = "${p0.dispatch-ms}")
  public void tick() {
    try {
      // 先清理无主执行，不让持续失联的 Runtime 把业务任务永久留在 RUNNING。
      repository.expireLeases();
      var owned = repository.owned(owner);
      if (owned.size() < 4) repository.claim(owner);
      for (var task : repository.owned(owner)) {
        try {
          com.fasterxml.jackson.databind.JsonNode snapshot;
          try {
            // 必须先查状态；上一次提交超时后，Python 可能实际上已经接受并继续运行。
            snapshot = engine.getStatus(task.executionId());
          } catch (TaskException e) {
            if (e.status() != 404) throw e;
            if (task.state() == TaskState.CANCEL_REQUESTED) {
              // 先保存取消墓碑，再投递原身份；迟到提交也不能启动已取消的执行。
              engine.cancel(task.executionId());
              engine.submit(task);
            } else engine.submit(task);
            snapshot = engine.getStatus(task.executionId());
          }
          if (task.state() == TaskState.CANCEL_REQUESTED) engine.cancel(task.executionId());
          // 查询在数据库事务之外；回写时 Repository 会重新验证租约、fence 和终态。
          var events = engine.getEvents(task.executionId(), task.runtimeEventCursor());
          repository.reconcile(task, snapshot, events);
        } catch (Exception e) {
          // 通信失败本轮不续租；只记录安全码，超期由数据库时钟驱动失联处理。
          log.warn(
              "event=runtime_poll_failed task_id={} run_id={} trace_id={} code={}",
              task.taskId(),
              task.executionId(),
              task.traceId(),
              e instanceof TaskException ? e.getMessage() : "POLL_FAILED");
        }
      }
    } catch (Exception e) {
      log.warn("event=dispatcher_unavailable code=DATABASE_OR_SCHEDULER_UNAVAILABLE");
    }
  }
}
