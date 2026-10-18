package com.enterprise.platform;

import static org.assertj.core.api.Assertions.*;

import com.enterprise.platform.api.TaskDtos.*;
import com.enterprise.platform.application.*;
import com.enterprise.platform.domain.*;
import com.enterprise.platform.infrastructure.TaskRepository;
import com.enterprise.platform.infrastructure.persistence.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 真实 PostgreSQL/Redis 集成测试；显式开关启用，关闭调度器避免后台消费测试队列。
 *
 * @author chomy
 **/
@SpringBootTest(properties = {"p0.scheduler-enabled=false", "p0.lease-seconds=15"})
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class PostgresTest {
  @Autowired TaskRepository repository;
  @Autowired TaskMapper tasks;
  @Autowired TaskService service;
  @Autowired PostgresFixtureMapper fixture;
  @Autowired ObjectMapper json;

  /**
   * 构造有限测试载荷；调用方提供独立幂等键。
   **/
  CreateTask body(String key) {
    return new CreateTask(key, new Input("analyze", List.of(1d, 2d, 3d)), new Limits(20, 120));
  }

  /**
   * 使用随机请求键创建测试任务，不复用已有业务键。
   **/
  TaskRecord create() {
    return service.create(body(UUID.randomUUID().toString()), "db-test");
  }

  /**
   * 同键同载荷返回相同 Task/Run，同键异载荷必须409；改变 trace 不能绕过幂等。
   **/
  @Test
  void idempotencyAndConflict() {
    var input = body(UUID.randomUUID().toString());
    var first = service.create(input, "trace");
    var second = service.create(input, "different-trace");
    assertThat(second.taskId()).isEqualTo(first.taskId());
    assertThat(second.executionId()).isEqualTo(first.executionId());
    var changed =
        new CreateTask(input.idempotencyKey(), new Input("changed", List.of(1d)), input.limits());
    assertThatThrownBy(() -> service.create(changed, "trace"))
        .isInstanceOf(TaskException.class)
        .hasMessage("IDEMPOTENCY_CONFLICT");
    repository.cancel(first.taskId());
  }

  /**
   * 同一幂等键并发创建只能产生一个 Task/Run 和首个事件。
   **/
  @Test
  void concurrentCreateIsIdempotent() throws Exception {
    var input = body(UUID.randomUUID().toString());
    var ids = new HashSet<String>();
    var runs = new HashSet<String>();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var gate = new CountDownLatch(1);
      var futures = new ArrayList<Future<TaskRecord>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  return service.create(input, "concurrent-create");
                }));
      gate.countDown();
      for (var future : futures) {
        var task = future.get(10, TimeUnit.SECONDS);
        ids.add(task.taskId());
        runs.add(task.executionId());
      }
    }
    assertThat(ids).hasSize(1);
    assertThat(runs).hasSize(1);
    var id = ids.iterator().next();
    assertThat(repository.events(id, 0)).hasSize(1);
    repository.cancel(id);
  }

  /**
   * 验证 MyBatis 映射的 JSONB、数据库默认值、时间及成功结果，保留真实 SQL NULL。
   **/
  @Test
  void jsonAndSnapshotMapping() {
    var task = create();
    assertThat(task.state()).isEqualTo(TaskState.QUEUED);
    assertThat(task.runStatus()).isEqualTo("CREATED");
    assertThat(task.fence()).isZero();
    assertThat(task.runtimeEventCursor()).isZero();
    assertThat(task.input().path("message").asText()).isEqualTo("analyze");
    assertThat(task.input().path("values").size()).isEqualTo(3);
    assertThat(task.limits().path("max_steps").asInt()).isEqualTo(20);
    assertThat(task.updatedAt()).isNotNull();
    assertThat(task.result()).isNull();
    assertThat(task.startedAt()).isNull();
    assertThat(task.checkpointThreadId()).isEqualTo(task.taskId() + "-" + task.executionId());

    var claimed = repository.claim("mapping-worker").orElseThrow();
    assertThat(claimed.taskId()).isEqualTo(task.taskId());
    var result = json.createObjectNode().put("message", "中文结果");
    var snapshot =
        json.createObjectNode()
            .put("task_id", task.taskId())
            .put("execution_id", task.executionId())
            .put("status", "SUCCEEDED")
            .put("started_at", "2026-10-18T01:00:00Z")
            .put("checkpoint_id", "checkpoint-mapping");
    snapshot.set("result", result);
    repository.reconcile(claimed, snapshot, json.createArrayNode());
    var completed = repository.get(task.taskId());
    assertThat(completed.result()).isEqualTo(result);
    assertThat(completed.startedAt()).isEqualTo(java.time.Instant.parse("2026-10-18T01:00:00Z"));
    assertThat(completed.errorCode()).isNull();
    assertThat(completed.checkpointId()).isEqualTo("checkpoint-mapping");
    var events = repository.events(task.taskId(), 0);
    assertThat(events).hasSize(3);
    assertThat(events.getLast().get("event_type")).isEqualTo("task_succeeded");
    assertThat(events.getLast().get("payload"))
        .isEqualTo(json.createObjectNode().put("state", "SUCCEEDED"));
    assertThat(events.getLast().get("timestamp")).isInstanceOf(java.time.Instant.class);
  }

  /**
   * 即使 Redis 锁丢失，旧快照的版本也不能覆盖已提交的取消状态。
   **/
  @Test
  void staleVersionCannotOverwriteCancellation() {
    var original = create();
    var cancelled = repository.cancel(original.taskId());
    assertThat(cancelled.version()).isGreaterThan(original.version());
    var changed =
        tasks.compareAndSetState(original.taskId(), original.version(), "QUEUED", "CLAIMED");
    assertThat(changed).isZero();
    assertThat(repository.get(original.taskId()).state()).isEqualTo(TaskState.CANCELLED);
    assertThat(repository.events(original.taskId(), 0)).hasSize(2);
  }

  /**
   * 同时释放8个领取线程，断言指定任务只有一个获胜者；不是用顺序执行模拟竞争。
   **/
  @Test
  void concurrentClaimIsAtomic() throws Exception {
    var task = create();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var gate = new CountDownLatch(1);
      var futures = new ArrayList<Future<Optional<TaskRecord>>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  return repository.claim("db-claim");
                }));
      gate.countDown();
      long count = 0;
      for (var future : futures)
        if (future
            .get(10, TimeUnit.SECONDS)
            .filter(t -> t.taskId().equals(task.taskId()))
            .isPresent()) count++;
      assertThat(count).isEqualTo(1);
    }
    repository.cancel(task.taskId());
    fixture.expireLease(task.taskId());
    repository.expireLeases();
  }

  /**
   * 只使本测试任务租约过期，再模拟旧持有者迟到成功；终态和 fence 必须保持有效。
   **/
  @Test
  void leaseExpiryFencesStaleWorker() {
    var task = create();
    var claimed = repository.claim("dead-worker").orElseThrow();
    assertThat(claimed.taskId()).isEqualTo(task.taskId());
    fixture.expireLease(task.taskId());
    repository.expireLeases();
    var snapshot =
        json.createObjectNode()
            .put("task_id", task.taskId())
            .put("execution_id", task.executionId())
            .put("status", "SUCCEEDED");
    repository.reconcile(claimed, snapshot, json.createArrayNode());
    assertThat(repository.get(task.taskId()).state()).isEqualTo(TaskState.FAILED);
    assertThat(repository.get(task.taskId()).errorCode()).isEqualTo("WORKER_LOST");
    assertThat(repository.get(task.taskId()).fence()).isGreaterThan(claimed.fence());
  }

  /**
   * 同时提交取消/完成，允许任一事务先赢，但最终必须只有一个一致业务终态事件。
   **/
  @Test
  void cancelAndCompletionRaceHasOneTerminalState() throws Exception {
    var task = create();
    var claimed = repository.claim("race-worker").orElseThrow();
    var snapshot =
        json.createObjectNode()
            .put("task_id", task.taskId())
            .put("execution_id", task.executionId())
            .put("status", "SUCCEEDED");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> repository.cancel(task.taskId()));
      var b = pool.submit(() -> repository.reconcile(claimed, snapshot, json.createArrayNode()));
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
    }
    var current = repository.get(task.taskId());
    assertThat(current.state()).isIn(TaskState.CANCELLED, TaskState.SUCCEEDED);
    assertThat(
            repository.events(task.taskId(), 0).stream()
                .filter(
                    e -> Set.of("task_succeeded", "task_cancelled").contains(e.get("event_type")))
                .count())
        .isEqualTo(1);
    assertThat(current.runStatus()).isEqualTo(current.state().name());
  }
}
