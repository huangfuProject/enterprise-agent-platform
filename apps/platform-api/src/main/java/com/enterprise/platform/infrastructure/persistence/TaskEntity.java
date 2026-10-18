package com.enterprise.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 业务任务表实体；与公共 DTO、TaskRecord 联合快照分离。
 *
 * @author chomy
 **/
@TableName(value = "p0_task", autoResultMap = true)
public class TaskEntity {
  /**
   * 业务任务标识，由应用生成。
   **/
  @TableId(type = IdType.INPUT)
  private String taskId;

  /**
   * P0 固定开发租户，不代表已实现租户授权。
   **/
  private String tenantId;

  /**
   * 任务修订版本，P0 固定为 1。
   **/
  private Integer revision;

  /**
   * 创建请求的唯一幂等键。
   **/
  private String idempotencyKey;

  /**
   * 规范化请求的 SHA-256 指纹。
   **/
  private String payloadHash;

  /**
   * 经过接口校验的任务输入。
   **/
  @TableField(typeHandler = JsonNodeTypeHandler.class)
  private JsonNode input;

  /**
   * 执行步骤和总时限。
   **/
  @TableField(typeHandler = JsonNodeTypeHandler.class)
  private JsonNode limits;

  /**
   * 首次创建请求的关联标识。
   **/
  private String traceId;

  /**
   * 平台权威业务状态，仅经状态机更新。
   **/
  private String state;

  /**
   * 受控写事务的乐观并发版本，由显式 CAS 更新，不使用乐观锁插件。
   **/
  private Long version;

  /**
   * 数据库记录的创建时间。
   **/
  private Instant createdAt;

  /**
   * 业务状态最后更新时间。
   **/
  private Instant updatedAt;

  /**
   * 读取业务任务标识，由应用生成。
   **/
  public String getTaskId() {
    return taskId;
  }

  /**
   * 设置业务任务标识，由应用生成。
   **/
  public void setTaskId(String taskId) {
    this.taskId = taskId;
  }

  /**
   * 读取P0 固定开发租户，不代表已实现租户授权。
   **/
  public String getTenantId() {
    return tenantId;
  }

  /**
   * 设置P0 固定开发租户，不代表已实现租户授权。
   **/
  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  /**
   * 读取任务修订版本，P0 固定为 1。
   **/
  public Integer getRevision() {
    return revision;
  }

  /**
   * 设置任务修订版本，P0 固定为 1。
   **/
  public void setRevision(Integer revision) {
    this.revision = revision;
  }

  /**
   * 读取创建请求的唯一幂等键。
   **/
  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  /**
   * 设置创建请求的唯一幂等键。
   **/
  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  /**
   * 读取规范化请求的 SHA-256 指纹。
   **/
  public String getPayloadHash() {
    return payloadHash;
  }

  /**
   * 设置规范化请求的 SHA-256 指纹。
   **/
  public void setPayloadHash(String payloadHash) {
    this.payloadHash = payloadHash;
  }

  /**
   * 读取经过接口校验的任务输入。
   **/
  public JsonNode getInput() {
    return input;
  }

  /**
   * 设置经过接口校验的任务输入。
   **/
  public void setInput(JsonNode input) {
    this.input = input;
  }

  /**
   * 读取执行步骤和总时限。
   **/
  public JsonNode getLimits() {
    return limits;
  }

  /**
   * 设置执行步骤和总时限。
   **/
  public void setLimits(JsonNode limits) {
    this.limits = limits;
  }

  /**
   * 读取首次创建请求的关联标识。
   **/
  public String getTraceId() {
    return traceId;
  }

  /**
   * 设置首次创建请求的关联标识。
   **/
  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  /**
   * 读取平台权威业务状态，仅经状态机更新。
   **/
  public String getState() {
    return state;
  }

  /**
   * 设置平台权威业务状态，仅经状态机更新。
   **/
  public void setState(String state) {
    this.state = state;
  }

  /**
   * 读取受控写事务的乐观并发版本，由显式 CAS 更新，不使用乐观锁插件。
   **/
  public Long getVersion() {
    return version;
  }

  /**
   * 设置受控写事务的乐观并发版本，由显式 CAS 更新，不使用乐观锁插件。
   **/
  public void setVersion(Long version) {
    this.version = version;
  }

  /**
   * 读取数据库记录的创建时间。
   **/
  public Instant getCreatedAt() {
    return createdAt;
  }

  /**
   * 设置数据库记录的创建时间。
   **/
  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  /**
   * 读取业务状态最后更新时间。
   **/
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /**
   * 设置业务状态最后更新时间。
   **/
  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
