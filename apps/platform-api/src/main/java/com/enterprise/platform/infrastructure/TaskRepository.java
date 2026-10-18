package com.enterprise.platform.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.platform.api.TaskDtos.CreateTask;
import com.enterprise.platform.application.TaskException;
import com.enterprise.platform.application.locking.DistributedLock;
import com.enterprise.platform.domain.*;
import com.enterprise.platform.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 任务、单次 Run 与业务事件的 PostgreSQL 持久化边界。
 *
 * <p>领取、取消、续租/终态与事件追加由任务级分布式锁和事务协调；HTTP 和模型调用不得进入这些事务。 Task 是业务权威，Run 记录 Runtime 观察、租约与 Checkpoint 引用；P0
 * 每个 Task 只有一次 Run。
 *
 * <p>依靠 Redis 任务锁协调竞态、唯一约束执行幂等、version/fence 拒绝陈旧回写；不承诺 exactly-once。
 *
 * @author chomy
 **/
@Repository
public class TaskRepository {
  private final TaskMapper tasks;
  private final RunMapper runs;
  private final EventMapper eventMapper;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final int leaseSeconds;
  private final DistributedLock locks;
  private final Duration lockWait;

  /**
   * 注入数据库事务、JSON 编码器和租约时长；租约期限一律按数据库时钟计算。
   **/
  public TaskRepository(
      TaskMapper tasks,
      RunMapper runs,
      EventMapper eventMapper,
      TransactionTemplate tx,
      ObjectMapper json,
      @Value("${p0.lease-seconds}") int leaseSeconds,
      DistributedLock locks,
      @Value("${p0.lock.wait-ms}") long lockWaitMs) {
    this.tasks = tasks;
    this.runs = runs;
    this.eventMapper = eventMapper;
    this.tx = tx;
    this.json = json;
    this.leaseSeconds = leaseSeconds;
    this.locks = locks;
    this.lockWait = Duration.ofMillis(lockWaitMs);
  }

  /**
   * 查询任务/运行快照；只读取现有记录，不创建 Run 或触发网络访问。
   **/
  public TaskRecord get(String id) {
    var task = tasks.selectSnapshot(id);
    if (task == null) throw new TaskException(404, "TASK_NOT_FOUND");
    return task;
  }

  /**
   * 固定顺序为取得任务锁、开启事务、提交/回滚、释放锁，禁止外层事务延迟实际提交。
   **/
  private <T> Optional<T> mutate(String taskId, Duration wait, Supplier<T> action) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("TASK_MUTATION_REQUIRES_NO_OUTER_TRANSACTION");
    return locks.tryExecute("task:" + taskId, wait, () -> tx.execute(status -> action.get()));
  }

  /**
   * 以唯一幂等键原子创建 Task、单次 Run 和首个事件。
   *
   * @param body 已校验的规范请求 DTO
   * @param hash Service 计算的载荷指纹
   * @param trace 首次创建请求的关联标识
   * @return 新任务或同键同载荷的已存在任务
   * @throws TaskException 相同幂等键绑定不同指纹时拒绝
   **/
  public TaskRecord create(CreateTask body, String hash, String trace) {
    return tx.execute(
        status -> {
          var id = UUID.randomUUID().toString();
          var run = UUID.randomUUID().toString();
          // 唯一约束负责并发去重，不能用“先查有没有、再插入”的非原子流程。
          var entity = new TaskEntity();
          entity.setTaskId(id);
          entity.setIdempotencyKey(body.idempotencyKey());
          entity.setPayloadHash(hash);
          entity.setInput(json.valueToTree(body.input()));
          entity.setLimits(json.valueToTree(body.limits()));
          entity.setTraceId(trace);
          entity.setState(TaskState.QUEUED.name());
          var changed = tasks.insertIfAbsent(entity);
          // ON CONFLICT 已等待并发创建事务结束；重复请求必须核对原始载荷指纹。
          if (changed == 0) {
            var existing =
                tasks.selectOne(
                    Wrappers.<TaskEntity>lambdaQuery()
                        .eq(TaskEntity::getIdempotencyKey, body.idempotencyKey()));
            if (existing == null) throw new IllegalStateException("IDEMPOTENCY_RECORD_MISSING");
            if (!hash.equals(existing.getPayloadHash()))
              throw new TaskException(409, "IDEMPOTENCY_CONFLICT");
            return get(existing.getTaskId());
          }
          var execution = new RunEntity();
          execution.setExecutionId(run);
          execution.setTaskId(id);
          // 每个执行使用独立 thread；同一 thread 内仍会产生多个 Checkpoint。
          execution.setCheckpointThreadId(id + "-" + run);
          execution.setRequestId(UUID.randomUUID().toString());
          // BaseMapper 默认跳过空字段，让数据库初始化 CREATED、fence、游标和时间。
          runs.insert(execution);
          var task = get(id);
          append(task, "task_created", Map.of());
          return task;
        });
  }

  /**
   * 在当前业务事务追加事件。
   *
   * <p>调用方须持有任务分布式锁并先成功执行 version CAS，或处于首次创建事务。
   * MAX(sequence)+1 本身不提供并发安全；Runtime sequence 保留在 payload，顶层为业务序号。
   **/
  private void append(TaskRecord task, String type, Object payload) {
    eventMapper.append(task.taskId(), task.executionId(), type, json.valueToTree(payload));
  }

  /**
   * 使用快照版本执行条件更新；即使目标状态不变也推进版本，保护事件与游标写入。
   *
   * <p>Redis 锁失效后若新持有者已更新版本，旧事务必须整体回滚，不能继续追加事件。
   **/
  private void writeState(TaskRecord task, TaskState target) {
    if (task.state() != target && !task.state().allows(target))
      throw new IllegalStateException("INVALID_STATE_TRANSITION");
    if (tasks.compareAndSetState(task.taskId(), task.version(), task.state().name(), target.name())
        != 1) throw new TaskException(409, "TASK_CONCURRENT_UPDATE");
  }

  /**
   * 状态变更事件仍在 Runtime 事件之后追加；版本更新与事件均属于同一事务。
   **/
  private void appendTransition(TaskRecord task, TaskState target) {
    if (task.state() != target)
      append(task, "task_" + target.name().toLowerCase(Locale.ROOT), Map.of("state", target));
  }

  /**
   * 状态迁移同时执行版本检查，任何冲突都由事务回滚。
   **/
  private void transition(TaskRecord task, TaskState target) {
    writeState(task, target);
    appendTransition(task, target);
  }

  /**
   * 普通查询候选任务，逐个非阻塞竞争任务锁，取得后重新检查 QUEUED 再条件更新。
   *
   * <p>不使用 SELECT FOR UPDATE；锁只覆盖短事务，不覆盖 Python HTTP 或 Agent 执行。
   **/
  public Optional<TaskRecord> claim(String owner) {
    for (var id : tasks.selectQueuedCandidates()) {
      try {
        var claimed =
            mutate(
                id,
                Duration.ZERO,
                () -> {
                  var task = get(id);
                  if (task.state() != TaskState.QUEUED) return null;
                  transition(task, TaskState.CLAIMED);
                  if (runs.claim(task.executionId(), owner, leaseSeconds) != 1)
                    throw new IllegalStateException("RUN_NOT_FOUND");
                  return get(id);
                });
        if (claimed.isPresent()) return claimed;
      } catch (TaskException e) {
        if (!"TASK_CONCURRENT_UPDATE".equals(e.getMessage())) throw e;
        // 锁失效引起的版本竞争只跳过此候选，下一轮仍可扫描。
      }
    }
    return Optional.empty();
  }

  /**
   * 读取本实例仍持有有效租约的最多四个活动任务，过期执行不能继续正常续租。
   **/
  public List<TaskRecord> owned(String owner) {
    return tasks.selectOwned(owner);
  }

  /**
   * 取得同一任务分布式锁后重新判断取消；锁竞争超时返回409，Redis 异常不降级执行。
   **/
  public TaskRecord cancel(String id) {
    return mutate(
            id,
            lockWait,
            () -> {
              var task = get(id);
              if (task.state().terminal() || task.state() == TaskState.CANCEL_REQUESTED)
                return task;
              if (task.state() == TaskState.QUEUED) {
                transition(task, TaskState.CANCELLED);
                runs.update(
                    null,
                    Wrappers.<RunEntity>lambdaUpdate()
                        .eq(RunEntity::getTaskId, id)
                        .set(RunEntity::getStatus, "CANCELLED"));
              } else transition(task, TaskState.CANCEL_REQUESTED);
              return get(id);
            })
        .orElseThrow(() -> new TaskException(409, "TASK_BUSY"));
  }

  /**
   * 有界扫描到期候选；锁内再次检查租约，避免把扫描后已成功续租的任务判为失联。
   **/
  public void expireLeases() {
    for (var candidate : tasks.selectExpiredCandidates()) {
      try {
        mutate(
            candidate.taskId(),
            Duration.ZERO,
            () -> {
              var task = get(candidate.taskId());
              if (task.state().terminal()) return false;
              boolean cancelled = task.state() == TaskState.CANCEL_REQUESTED;
              transition(task, cancelled ? TaskState.CANCELLED : TaskState.FAILED);
              // 条件更新重新验证数据库租约；若期间已续租，回滚状态和事件。
              if (runs.expire(task.executionId(), cancelled ? "CANCELLED" : "FAILED") != 1)
                throw new TaskException(409, "TASK_CONCURRENT_UPDATE");
              return true;
            });
      } catch (TaskException e) {
        if (!"TASK_CONCURRENT_UPDATE".equals(e.getMessage())) throw e;
        // 扫描后成功续租是正常竞争，不阻塞其他到期任务。
      }
    }
  }

  /**
   * 把有效持有者查询的快照与增量事件事务性导入控制平面。
   *
   * @param claimed 发起查询时持有的 owner/fence 快照
   * @param snapshot Runtime 回传的执行快照，先校验 Task/Run 绑定
   * @param events 按运行内 sequence 排序的增量事件
   * <p>重新校验版本与租约可阻止查询期间失效的旧 Worker 覆盖结果。取消先提交时，不发布迟到成功。
   **/
  public void reconcile(TaskRecord claimed, JsonNode snapshot, JsonNode events) {
    mutate(
        claimed.taskId(),
        lockWait,
        () -> {
          var current = get(claimed.taskId());
          var valid =
              runs.countValidLease(claimed.executionId(), claimed.fence(), claimed.leaseOwner());
          // 即使 Runtime 返回成功，失效租约或既有终态也不能被覆盖。
          if (valid == 0 || current.state().terminal()) return false;
          if (!claimed.executionId().equals(snapshot.path("execution_id").asText())
              || !claimed.taskId().equals(snapshot.path("task_id").asText()))
            throw new IllegalStateException("RUNTIME_CONTRACT_MISMATCH");
          String runStatus = snapshot.path("status").asText();
          var target =
              switch (runStatus) {
                case "RUNNING" ->
                    current.state() == TaskState.CANCEL_REQUESTED
                        ? TaskState.CANCEL_REQUESTED
                        : TaskState.RUNNING;
                  // 取消事务先提交时，即使 Python 刚完成，也以平台取消意图为准。
                case "SUCCEEDED" ->
                    current.state() == TaskState.CANCEL_REQUESTED
                        ? TaskState.CANCELLED
                        : TaskState.SUCCEEDED;
                case "FAILED" -> TaskState.FAILED;
                case "CANCELLED" -> TaskState.CANCELLED;
                case "ACCEPTED" -> current.state();
                default -> throw new IllegalStateException("INVALID_RUNTIME_STATUS");
              };
          if (target == TaskState.CANCELLED && current.state() != TaskState.CANCEL_REQUESTED)
            throw new IllegalStateException("UNREQUESTED_CANCEL");
          // CAS 必须先于事件追加；陈旧版本不能参与序号分配。
          writeState(current, target);
          long cursor = current.runtimeEventCursor();
          for (var event : events) {
            long seq = event.path("sequence").asLong();
            // 轮询允许重复投递事件；已导入部分跳过，但事件缺口必须失败而不能掩盖。
            if (seq <= cursor) continue;
            if (seq != cursor + 1
                || !claimed.executionId().equals(event.path("execution_id").asText()))
              throw new IllegalStateException("RUNTIME_EVENT_GAP");
            append(current, event.path("event_type").asText(), event);
            cursor = seq;
          }
          appendTransition(current, target);
          var execution = new RunEntity();
          execution.setExecutionId(current.executionId());
          execution.setStatus(target.terminal() ? target.name() : runStatus);
          execution.setRuntimeEventCursor(cursor);
          execution.setResult(target == TaskState.SUCCEEDED ? snapshot.get("result") : null);
          execution.setCheckpointId(text(snapshot, "checkpoint_id"));
          execution.setErrorCode(text(snapshot, "error_code"));
          execution.setErrorMessage(text(snapshot, "error_message"));
          if (runs.reconcile(
                  execution,
                  text(snapshot, "started_at"),
                  leaseSeconds,
                  claimed.fence(),
                  claimed.leaseOwner())
              != 1) throw new TaskException(409, "TASK_CONCURRENT_UPDATE");
          return true;
        });
  }

  /**
   * 读取可空 JSON 文本字段；保留 SQL NULL，避免把字符串 null 写入数据库。
   **/
  private String text(JsonNode node, String key) {
    var value = node.get(key);
    return value == null || value.isNull() ? null : value.asText();
  }

  /**
   * 查询任务内序号大于 after 的最多500条事件，并补齐统一关联标识。
   **/
  public List<Map<String, Object>> events(String id, long after) {
    var task = get(id);
    return eventMapper.selectAfter(id, after).stream()
        .map(
            event ->
                Map.<String, Object>of(
                    "task_id", id,
                    "execution_id", event.executionId(),
                    "sequence", event.sequence(),
                    "event_type", event.eventType(),
                    "trace_id", task.traceId(),
                    "contract_version", "v1",
                    "timestamp", event.timestamp(),
                    "payload", event.payload()))
        .toList();
  }
}
