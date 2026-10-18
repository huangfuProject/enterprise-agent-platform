package com.enterprise.platform.api;

import com.enterprise.platform.domain.TaskRecord;
import com.enterprise.platform.domain.TaskState;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 对外稳定任务投影，屏蔽内部租约持有者、fence 和事件导入游标。
 *
 * <p>state 为业务权威，runStatus 为执行观察；Checkpoint 引用不表示任意副作用可恢复。 P0 revision 固定为 1，尚未实现任务修订接口。
 *
 * @author chomy
 **/
public record TaskView(
    String contractVersion,
    String taskId,
    String executionId,
    int revision,
    String idempotencyKey,
    String traceId,
    String requestId,
    String checkpointThreadId,
    TaskState state,
    String runStatus,
    Instant startedAt,
    Instant updatedAt,
    String errorCode,
    String errorMessage,
    JsonNode result,
    String checkpointId) {
  /**
   * 从内部快照映射公开字段；显式指定协议版本和当前唯一修订版本。
   **/
  public static TaskView from(TaskRecord task) {
    return new TaskView(
        "v1",
        task.taskId(),
        task.executionId(),
        1,
        task.idempotencyKey(),
        task.traceId(),
        task.requestId(),
        task.checkpointThreadId(),
        task.state(),
        task.runStatus(),
        task.startedAt(),
        task.updatedAt(),
        task.errorCode(),
        task.errorMessage(),
        task.result(),
        task.checkpointId());
  }
}
