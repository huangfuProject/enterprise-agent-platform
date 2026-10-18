package com.enterprise.platform.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 应用内部的任务/运行联合快照；用于调度与持久化映射，不作为公共响应 DTO。
 *
 * @param taskId 业务任务标识
 * @param executionId 当前尝试/AgentRun 标识
 * @param idempotencyKey 创建任务的稳定幂等键
 * @param traceId 跨进程关联标识
 * @param requestId Runtime 请求标识，重复投递保持不变
 * @param checkpointThreadId 图状态命名空间，一个 thread 可有多个 Checkpoint
 * @param state Java 权威业务状态
 * @param runStatus 最近观察的执行状态，与业务状态分开存储
 * @param leaseOwner 当前调度器实例标识
 * @param fence 单调执行代号，过期持有者不能凭旧值回写
 * @param input 已验证的业务输入
 * @param limits 步骤/执行期限约束
 * @param startedAt Runtime 真正开始执行的时间，可为空
 * @param updatedAt 业务 Task 的更新时间，不是最近一次心跳时间
 * @param errorCode 安全错误码
 * @param errorMessage 受控错误说明
 * @param result 经 Runtime 校验的结构化结果，可为空
 * @param checkpointId 最终可关联的图快照标识，可为空
 * @param runtimeEventCursor 已导入 Java 的 Runtime 事件序号
 * @param version 数据库乐观并发版本，每次受控任务写事务递增
 *
 * @author chomy
 **/
public record TaskRecord(
    String taskId,
    String executionId,
    String idempotencyKey,
    String traceId,
    String requestId,
    String checkpointThreadId,
    TaskState state,
    String runStatus,
    String leaseOwner,
    long fence,
    JsonNode input,
    JsonNode limits,
    Instant startedAt,
    Instant updatedAt,
    String errorCode,
    String errorMessage,
    JsonNode result,
    String checkpointId,
    long runtimeEventCursor,
    long version) {}
