package com.enterprise.platform.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 发往 Python 的 v1 执行请求；只包含稳定协议字段。
 *
 * <p>与 Python TaskExecutionRequest 对齐；不得把 TaskRecord 或 LangGraph State 直接序列化为协议。
 *
 * @param contractVersion 协议版本，当前固定 v1
 * @param taskId 业务任务标识
 * @param executionId 当前执行尝试标识；重复投递必须保持不变
 * @param idempotencyKey 同一任务修订的持久去重键
 * @param traceId 跨 Java/Python/事件的关联标识
 * @param requestId 同一执行请求的稳定请求标识
 * @param checkpointThreadId 本次执行独占的图状态命名空间
 * @param agentDefinition 已注册的主 Agent 模板，P0 仅支持 main-agent
 * @param input 有限的任务消息和样例数据
 * @param limits 共享步骤预算与总执行时限
 *
 * @author chomy
 **/
public record RuntimeRequest(
    @NotNull @Pattern(regexp = "v1") String contractVersion,
    @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String taskId,
    @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String executionId,
    @NotBlank @Size(max = 100) String idempotencyKey,
    @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String traceId,
    @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String requestId,
    @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,160}") String checkpointThreadId,
    @NotNull @Pattern(regexp = "main-agent") String agentDefinition,
    @NotNull @Valid TaskDtos.Input input,
    @NotNull @Valid TaskDtos.Limits limits) {}
