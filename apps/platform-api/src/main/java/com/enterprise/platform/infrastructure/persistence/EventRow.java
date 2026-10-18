package com.enterprise.platform.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 事件查询投影；事件使用 task_id/sequence 复合主键，不伪造 BaseMapper 的单列主键。
 *
 * @author chomy
 **/
public record EventRow(
    String executionId, long sequence, String eventType, Instant timestamp, JsonNode payload) {}
