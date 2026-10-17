package com.enterprise.platform.infrastructure;

import com.enterprise.platform.api.TaskDtos.CreateTask;
import com.enterprise.platform.application.TaskException;
import com.enterprise.platform.domain.*;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 任务、单次 Run 与业务事件的 PostgreSQL 持久化边界。
 *
 * <p>领取、取消、续租/终态与事件追加在事务内串行化；HTTP 和模型调用不得进入这些事务。 Task 是业务权威，Run 记录 Runtime 观察、租约与 Checkpoint 引用；P0
 * 每个 Task 只有一次 Run。
 *
 * <p>依靠 Task 行锁协调竞态、唯一约束执行幂等、fence 拒绝旧持有者回写；不承诺 exactly-once。
 */
@Repository
public class TaskRepository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final int leaseSeconds;
  // 共同投影保留两个状态来源，避免用 Runtime 快照覆盖业务 Task 的治理语义。
  private static final String SELECT =
      """
        SELECT t.*,r.execution_id,r.checkpoint_thread_id,r.request_id,r.status AS run_status,
          r.lease_owner,r.fence,r.started_at,r.error_code,r.error_message,r.result,r.checkpoint_id,
          r.runtime_event_cursor
        FROM p0_task t JOIN p0_run r ON r.task_id=t.task_id
        """;

  /** 注入数据库事务、JSON 编码器和租约时长；租约期限一律按数据库时钟计算。 */
  public TaskRepository(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      ObjectMapper json,
      @Value("${p0.lease-seconds}") int leaseSeconds) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
    this.leaseSeconds = leaseSeconds;
  }

  /** 还原持久化 JSON；损坏数据必须显式失败，不用空结果掩盖。 */
  private JsonNode parse(String value) {
    if (value == null) return null;
    try {
      return json.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException("INVALID_STORED_JSON", e);
    }
  }

  /** 将可空数据库时间戳映射为绝对时间，不用应用默认时区猜测含义。 */
  private Instant instant(ResultSet rs, String key) throws SQLException {
    var value = rs.getTimestamp(key);
    return value == null ? null : value.toInstant();
  }

  /** 联合映射 Task 和 Run；保留业务状态、执行状态、心跳与 Checkpoint 的职责区别。 */
  private TaskRecord row(ResultSet rs, int number) throws SQLException {
    return new TaskRecord(
        rs.getString("task_id"),
        rs.getString("execution_id"),
        rs.getString("idempotency_key"),
        rs.getString("trace_id"),
        rs.getString("request_id"),
        rs.getString("checkpoint_thread_id"),
        TaskState.valueOf(rs.getString("state")),
        rs.getString("run_status"),
        rs.getString("lease_owner"),
        rs.getLong("fence"),
        parse(rs.getString("input")),
        parse(rs.getString("limits")),
        instant(rs, "started_at"),
        instant(rs, "updated_at"),
        rs.getString("error_code"),
        rs.getString("error_message"),
        parse(rs.getString("result")),
        rs.getString("checkpoint_id"),
        rs.getLong("runtime_event_cursor"));
  }

  /** 使用统一 Jackson 配置编码数据库 JSONB 和事件载荷。 */
  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 查询任务/运行快照；只读取现有记录，不创建 Run 或触发网络访问。 */
  public TaskRecord get(String id) {
    var rows = jdbc.query(SELECT + " WHERE t.task_id=?", this::row, id);
    if (rows.isEmpty()) throw new TaskException(404, "TASK_NOT_FOUND");
    return rows.getFirst();
  }

  /**
   * 在当前事务取得 Task 行锁后读取最新快照。
   *
   * <p>调用方必须在 TransactionTemplate 中使用；该锁串行化取消、完成和事件追加。
   */
  private TaskRecord lock(String id) {
    jdbc.queryForObject("SELECT task_id FROM p0_task WHERE task_id=? FOR UPDATE", String.class, id);
    return get(id);
  }

  /**
   * 以唯一幂等键原子创建 Task、单次 Run 和首个事件。
   *
   * @param body 已校验的规范请求 DTO
   * @param hash Service 计算的载荷指纹
   * @param trace 首次创建请求的关联标识
   * @return 新任务或同键同载荷的已存在任务
   * @throws TaskException 相同幂等键绑定不同指纹时拒绝
   */
  public TaskRecord create(CreateTask body, String hash, String trace) {
    return tx.execute(
        status -> {
          var id = UUID.randomUUID().toString();
          var run = UUID.randomUUID().toString();
          // 唯一约束负责并发去重，不能用“先查有没有、再插入”的非原子流程。
          var changed =
              jdbc.update(
                  """
                INSERT INTO p0_task(task_id,idempotency_key,payload_hash,input,limits,trace_id,state)
                VALUES (?,?,?,?::jsonb,?::jsonb,?,'QUEUED') ON CONFLICT(idempotency_key) DO NOTHING
                """,
                  id,
                  body.idempotencyKey(),
                  hash,
                  encode(body.input()),
                  encode(body.limits()),
                  trace);
          // ON CONFLICT 已等待并发创建事务结束；重复请求必须核对原始载荷指纹。
          if (changed == 0) {
            var existing =
                jdbc.queryForMap(
                    "SELECT task_id,payload_hash FROM p0_task WHERE idempotency_key=?",
                    body.idempotencyKey());
            if (!hash.equals(existing.get("payload_hash")))
              throw new TaskException(409, "IDEMPOTENCY_CONFLICT");
            return get(existing.get("task_id").toString());
          }
          jdbc.update(
              "INSERT INTO p0_run(execution_id,task_id,checkpoint_thread_id,request_id) VALUES (?,?,?,?)",
              run,
              id,
              // 每个执行使用独立 thread；同一 thread 内仍会产生多个 Checkpoint。
              id + "-" + run,
              UUID.randomUUID().toString());
          var task = get(id);
          append(task, "task_created", Map.of());
          return task;
        });
  }

  /**
   * 在当前业务事务追加事件。
   *
   * <p>调用方须持有任务行锁，或处于该任务首次创建的独占事务；否则 max(sequence)+1 本身不能提供并发安全。Runtime 的 sequence 保留在
   * payload，顶层为业务序号。
   */
  private void append(TaskRecord task, String type, Object payload) {
    jdbc.update(
        """
            INSERT INTO p0_event(task_id,sequence,execution_id,event_type,payload)
            SELECT ?,coalesce(max(sequence),0)+1,?,?,?::jsonb FROM p0_event WHERE task_id=?
            """,
        task.taskId(),
        task.executionId(),
        type,
        encode(payload),
        task.taskId());
  }

  /**
   * 校验合法迁移，更新 Task 状态/版本并在同一事务追加业务事件。
   *
   * <p>当前实现依靠行锁串行化；version 用于追踪变更，不是另一个未经实现的 CAS 协议。
   */
  private void transition(TaskRecord task, TaskState target) {
    if (task.state() == target) return;
    if (!task.state().allows(target)) throw new IllegalStateException("INVALID_STATE_TRANSITION");
    jdbc.update(
        "UPDATE p0_task SET state=?,version=version+1,updated_at=clock_timestamp() WHERE task_id=?",
        target.name(),
        task.taskId());
    append(task, "task_" + target.name().toLowerCase(Locale.ROOT), Map.of("state", target));
  }

  /**
   * 原子领取一个排队任务并绑定有效租约。
   *
   * @param owner 本次调度器实例标识
   * @return 领取后的最新快照；无可领取记录时为空
   *     <p>SKIP LOCKED 让其他 Worker 跳过已锁任务；事务提交前不能发起 Runtime HTTP 调用。
   */
  public Optional<TaskRecord> claim(String owner) {
    return tx.execute(
        status -> {
          // Task 行锁覆盖随后的状态迁移与 Run 租约写入，其他领取者跳过此行。
          var ids =
              jdbc.queryForList(
                  "SELECT task_id FROM p0_task WHERE state='QUEUED' ORDER BY created_at "
                      + "LIMIT 1 FOR UPDATE SKIP LOCKED",
                  String.class);
          if (ids.isEmpty()) return Optional.empty();
          var task = get(ids.getFirst());
          transition(task, TaskState.CLAIMED);
          jdbc.update(
              """
                UPDATE p0_run SET lease_owner=?,lease_expires_at=clock_timestamp()+(? * interval '1 second'),
                 heartbeat_at=clock_timestamp(),fence=fence+1,status='CLAIMED',updated_at=clock_timestamp()
                WHERE execution_id=?
                """,
              owner,
              leaseSeconds,
              task.executionId());
          return Optional.of(get(task.taskId()));
        });
  }

  /** 读取本实例仍持有有效租约的最多四个活动任务，过期执行不能继续正常续租。 */
  public List<TaskRecord> owned(String owner) {
    return jdbc.query(
        SELECT
            + " WHERE r.lease_owner=? AND r.lease_expires_at>clock_timestamp() "
            + "AND t.state IN ('CLAIMED','RUNNING','CANCEL_REQUESTED') ORDER BY t.created_at LIMIT 4",
        this::row,
        owner);
  }

  /**
   * 在任务行锁内记录取消意图，与完成事务竞争。
   *
   * <p>QUEUED 尚未投递，可直接取消；已领取/运行的任务先进入 CANCEL_REQUESTED。 已有终态和重复取消保持幂等，实际停止由 Runtime 处理。
   */
  public TaskRecord cancel(String id) {
    return tx.execute(
        status -> {
          get(id);
          // 先锁后判断，取消与完成不能同时依据同一份过期 RUNNING 快照写终态。
          var task = lock(id);
          if (task.state().terminal() || task.state() == TaskState.CANCEL_REQUESTED) return task;
          if (task.state() == TaskState.QUEUED) {
            transition(task, TaskState.CANCELLED);
            jdbc.update("UPDATE p0_run SET status='CANCELLED' WHERE task_id=?", id);
          } else transition(task, TaskState.CANCEL_REQUESTED);
          return get(id);
        });
  }

  /**
   * 失败关闭过期执行并提高 fence，不自动重新排队或重放。
   *
   * <p>已有取消意图时业务状态收敛为 CANCELLED；其他情况为 FAILED/WORKER_LOST。
   */
  public void expireLeases() {
    tx.executeWithoutResult(
        status -> {
          var tasks =
              jdbc.query(
                  SELECT
                      + " WHERE t.state IN ('CLAIMED','RUNNING','CANCEL_REQUESTED') "
                      + "AND r.lease_expires_at<clock_timestamp() FOR UPDATE OF t SKIP LOCKED",
                  this::row);
          for (var task : tasks) {
            boolean cancelled = task.state() == TaskState.CANCEL_REQUESTED;
            // 提高 fence 使已在网络途中的旧查询结果失效，不能用旧身份重新续租。
            transition(task, cancelled ? TaskState.CANCELLED : TaskState.FAILED);
            jdbc.update(
                """
                    UPDATE p0_run SET status=?,fence=fence+1,error_code=?,error_message=?,updated_at=clock_timestamp()
                    WHERE execution_id=?
                    """,
                cancelled ? "CANCELLED" : "FAILED",
                "WORKER_LOST",
                "Lease expired; uncertain execution requires reconciliation",
                task.executionId());
          }
        });
  }

  /**
   * 把有效持有者查询的快照与增量事件事务性导入控制平面。
   *
   * @param claimed 发起查询时持有的 owner/fence 快照
   * @param snapshot Runtime 回传的执行快照，先校验 Task/Run 绑定
   * @param events 按运行内 sequence 排序的增量事件
   *     <p>重新校验租约可阻止查询期间失效的旧 Worker 覆盖结果。取消先提交时，不发布迟到成功。
   */
  public void reconcile(TaskRecord claimed, JsonNode snapshot, JsonNode events) {
    tx.executeWithoutResult(
        status -> {
          var current = lock(claimed.taskId());
          var valid =
              jdbc.queryForObject(
                  "SELECT count(*) FROM p0_run WHERE execution_id=? AND fence=? "
                      + "AND lease_owner=? AND lease_expires_at>clock_timestamp()",
                  Integer.class,
                  claimed.executionId(),
                  claimed.fence(),
                  claimed.leaseOwner());
          // 即使 Runtime 返回成功，失效租约或既有终态也不能被覆盖。
          if (valid == 0 || current.state().terminal()) return;
          if (!claimed.executionId().equals(snapshot.path("execution_id").asText())
              || !claimed.taskId().equals(snapshot.path("task_id").asText()))
            throw new IllegalStateException("RUNTIME_CONTRACT_MISMATCH");
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
          // 事件、游标、状态与续租同事务提交；任何校验失败都会整体回滚。
          transition(current, target);
          jdbc.update(
              """
                UPDATE p0_run SET status=?,started_at=coalesce(started_at,?::timestamptz),
                 heartbeat_at=clock_timestamp(),lease_expires_at=clock_timestamp()+(? * interval '1 second'),
                 updated_at=clock_timestamp(),runtime_event_cursor=?,result=?::jsonb,checkpoint_id=?,
                 error_code=?,error_message=? WHERE execution_id=?
                """,
              target.terminal() ? target.name() : runStatus,
              text(snapshot, "started_at"),
              leaseSeconds,
              cursor,
              target == TaskState.SUCCEEDED ? encode(snapshot.get("result")) : null,
              text(snapshot, "checkpoint_id"),
              text(snapshot, "error_code"),
              text(snapshot, "error_message"),
              current.executionId());
        });
  }

  /** 读取可空 JSON 文本字段；保留 SQL NULL，避免把字符串 null 写入数据库。 */
  private String text(JsonNode node, String key) {
    var value = node.get(key);
    return value == null || value.isNull() ? null : value.asText();
  }

  /** 查询任务内序号大于 after 的最多500条事件，并补齐统一关联标识。 */
  public List<Map<String, Object>> events(String id, long after) {
    var task = get(id);
    return jdbc.query(
        "SELECT * FROM p0_event WHERE task_id=? AND sequence>? ORDER BY sequence LIMIT 500",
        (rs, n) ->
            Map.<String, Object>of(
                "task_id",
                id,
                "execution_id",
                rs.getString("execution_id"),
                "sequence",
                rs.getLong("sequence"),
                "event_type",
                rs.getString("event_type"),
                "trace_id",
                task.traceId(),
                "contract_version",
                "v1",
                "timestamp",
                rs.getTimestamp("timestamp").toInstant(),
                "payload",
                parse(rs.getString("payload"))),
        id,
        after);
  }
}
