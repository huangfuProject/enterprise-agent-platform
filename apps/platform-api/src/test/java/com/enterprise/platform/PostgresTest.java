package com.enterprise.platform;

import static org.assertj.core.api.Assertions.*;

import com.enterprise.platform.api.TaskDtos.*;
import com.enterprise.platform.application.*;
import com.enterprise.platform.domain.*;
import com.enterprise.platform.infrastructure.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实 PostgreSQL 集成测试；显式开关启用，关闭调度器避免后台消费测试队列。 */
@SpringBootTest(properties = {"p0.scheduler-enabled=false", "p0.lease-seconds=15"})
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class PostgresTest {
  @Autowired TaskRepository repository;
  @Autowired TaskService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;

  /** 构造有限测试载荷；调用方提供独立幂等键。 */
  CreateTask body(String key) {
    return new CreateTask(key, new Input("analyze", List.of(1d, 2d, 3d)), new Limits(20, 120));
  }

  /** 使用随机请求键创建测试任务，不复用已有业务键。 */
  TaskRecord create() {
    return service.create(body(UUID.randomUUID().toString()), "db-test");
  }

  /** 同键同载荷返回相同 Task/Run，同键异载荷必须409；改变 trace 不能绕过幂等。 */
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

  /** 同时释放8个领取线程，断言指定任务只有一个获胜者；不是用顺序执行模拟竞争。 */
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
    jdbc.update(
        "UPDATE p0_run SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE task_id=?",
        task.taskId());
    repository.expireLeases();
  }

  /** 只使本测试任务租约过期，再模拟旧持有者迟到成功；终态和 fence 必须保持有效。 */
  @Test
  void leaseExpiryFencesStaleWorker() {
    var task = create();
    var claimed = repository.claim("dead-worker").orElseThrow();
    assertThat(claimed.taskId()).isEqualTo(task.taskId());
    jdbc.update(
        "UPDATE p0_run SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE task_id=?",
        task.taskId());
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

  /** 同时提交取消/完成，允许任一事务先赢，但最终必须只有一个一致业务终态事件。 */
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
