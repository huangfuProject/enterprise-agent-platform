package com.enterprise.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 单次执行表实体；数据库默认值负责初始化状态、游标与 fence。
 *
 * @author chomy
 **/
@TableName(value = "p0_run", autoResultMap = true)
public class RunEntity {
  /**
   * 单次执行标识，由应用生成。
   **/
  @TableId(type = IdType.INPUT)
  private String executionId;

  /**
   * 所属业务任务；P0 每个任务只有一次执行。
   **/
  private String taskId;

  /**
   * LangGraph 状态命名空间，区别于单个 Checkpoint ID。
   **/
  private String checkpointThreadId;

  /**
   * 提交 Runtime 的稳定请求标识。
   **/
  private String requestId;

  /**
   * 最近观察到的执行状态。
   **/
  private String status;

  /**
   * 当前负责对账的调度器实例。
   **/
  private String leaseOwner;

  /**
   * 数据库时钟计算的租约到期时间。
   **/
  private Instant leaseExpiresAt;

  /**
   * 最近成功续租时间。
   **/
  private Instant heartbeatAt;

  /**
   * 持有者代数，拒绝失效实例回写。
   **/
  private Long fence;

  /**
   * Runtime 真正开始执行的时间。
   **/
  private Instant startedAt;

  /**
   * 执行记录最后更新时间。
   **/
  private Instant updatedAt;

  /**
   * 通过输出校验的结构化结果。
   **/
  @TableField(typeHandler = JsonNodeTypeHandler.class)
  private JsonNode result;

  /**
   * 已持久化的 Checkpoint 引用。
   **/
  private String checkpointId;

  /**
   * 统一安全错误码。
   **/
  private String errorCode;

  /**
   * 受控错误说明，不存放原始供应商异常。
   **/
  private String errorMessage;

  /**
   * 已导入平台的 Runtime 事件序号。
   **/
  private Long runtimeEventCursor;

  /**
   * 读取单次执行标识，由应用生成。
   **/
  public String getExecutionId() {
    return executionId;
  }

  /**
   * 设置单次执行标识，由应用生成。
   **/
  public void setExecutionId(String executionId) {
    this.executionId = executionId;
  }

  /**
   * 读取所属业务任务；P0 每个任务只有一次执行。
   **/
  public String getTaskId() {
    return taskId;
  }

  /**
   * 设置所属业务任务；P0 每个任务只有一次执行。
   **/
  public void setTaskId(String taskId) {
    this.taskId = taskId;
  }

  /**
   * 读取LangGraph 状态命名空间，区别于单个 Checkpoint ID。
   **/
  public String getCheckpointThreadId() {
    return checkpointThreadId;
  }

  /**
   * 设置LangGraph 状态命名空间，区别于单个 Checkpoint ID。
   **/
  public void setCheckpointThreadId(String checkpointThreadId) {
    this.checkpointThreadId = checkpointThreadId;
  }

  /**
   * 读取提交 Runtime 的稳定请求标识。
   **/
  public String getRequestId() {
    return requestId;
  }

  /**
   * 设置提交 Runtime 的稳定请求标识。
   **/
  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }

  /**
   * 读取最近观察到的执行状态。
   **/
  public String getStatus() {
    return status;
  }

  /**
   * 设置最近观察到的执行状态。
   **/
  public void setStatus(String status) {
    this.status = status;
  }

  /**
   * 读取当前负责对账的调度器实例。
   **/
  public String getLeaseOwner() {
    return leaseOwner;
  }

  /**
   * 设置当前负责对账的调度器实例。
   **/
  public void setLeaseOwner(String leaseOwner) {
    this.leaseOwner = leaseOwner;
  }

  /**
   * 读取数据库时钟计算的租约到期时间。
   **/
  public Instant getLeaseExpiresAt() {
    return leaseExpiresAt;
  }

  /**
   * 设置数据库时钟计算的租约到期时间。
   **/
  public void setLeaseExpiresAt(Instant leaseExpiresAt) {
    this.leaseExpiresAt = leaseExpiresAt;
  }

  /**
   * 读取最近成功续租时间。
   **/
  public Instant getHeartbeatAt() {
    return heartbeatAt;
  }

  /**
   * 设置最近成功续租时间。
   **/
  public void setHeartbeatAt(Instant heartbeatAt) {
    this.heartbeatAt = heartbeatAt;
  }

  /**
   * 读取持有者代数，拒绝失效实例回写。
   **/
  public Long getFence() {
    return fence;
  }

  /**
   * 设置持有者代数，拒绝失效实例回写。
   **/
  public void setFence(Long fence) {
    this.fence = fence;
  }

  /**
   * 读取Runtime 真正开始执行的时间。
   **/
  public Instant getStartedAt() {
    return startedAt;
  }

  /**
   * 设置Runtime 真正开始执行的时间。
   **/
  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  /**
   * 读取执行记录最后更新时间。
   **/
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /**
   * 设置执行记录最后更新时间。
   **/
  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  /**
   * 读取通过输出校验的结构化结果。
   **/
  public JsonNode getResult() {
    return result;
  }

  /**
   * 设置通过输出校验的结构化结果。
   **/
  public void setResult(JsonNode result) {
    this.result = result;
  }

  /**
   * 读取已持久化的 Checkpoint 引用。
   **/
  public String getCheckpointId() {
    return checkpointId;
  }

  /**
   * 设置已持久化的 Checkpoint 引用。
   **/
  public void setCheckpointId(String checkpointId) {
    this.checkpointId = checkpointId;
  }

  /**
   * 读取统一安全错误码。
   **/
  public String getErrorCode() {
    return errorCode;
  }

  /**
   * 设置统一安全错误码。
   **/
  public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
  }

  /**
   * 读取受控错误说明，不存放原始供应商异常。
   **/
  public String getErrorMessage() {
    return errorMessage;
  }

  /**
   * 设置受控错误说明，不存放原始供应商异常。
   **/
  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  /**
   * 读取已导入平台的 Runtime 事件序号。
   **/
  public Long getRuntimeEventCursor() {
    return runtimeEventCursor;
  }

  /**
   * 设置已导入平台的 Runtime 事件序号。
   **/
  public void setRuntimeEventCursor(Long runtimeEventCursor) {
    this.runtimeEventCursor = runtimeEventCursor;
  }
}
