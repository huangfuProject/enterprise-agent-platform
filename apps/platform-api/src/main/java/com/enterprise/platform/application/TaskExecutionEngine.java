package com.enterprise.platform.application;

import com.enterprise.platform.domain.TaskRecord;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 可替换的异步执行引擎边界，当前实现通过 HTTP 调用独立 Runtime。
 *
 * <p>提交确认、执行开始和完成是不同事实。HTTP 超时只表示结果未知；调用方必须 以原 executionId 对账，不得据此推断执行已停止或立即创建新尝试。
 *
 * @author chomy
 **/
public interface TaskExecutionEngine {
  /**
   * 投递同一执行请求；幂等身份取自 task，超时后不得更换 executionId。
   **/
  void submit(TaskRecord task);

  /**
   * 查询 Runtime 执行快照；由控制平面校验后再映射业务状态。
   **/
  JsonNode getStatus(String executionId);

  /**
   * 读取指定执行的增量事件，after 是 Runtime 内部序号的排他游标。
   **/
  JsonNode getEvents(String executionId, long after);

  /**
   * 持久化协作取消意图；实现应允许取消先于迟到的提交抵达。
   **/
  void cancel(String executionId);
}
