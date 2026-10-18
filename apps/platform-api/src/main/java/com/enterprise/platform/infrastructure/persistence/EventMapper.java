package com.enterprise.platform.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 复合主键事件表的专用 Mapper；序号分配依赖调用方 任务分布式锁和 version CAS，或首次创建事务。
 *
 * @author chomy
 **/
@Mapper
public interface EventMapper {
  /**
   * 在当前任务事务中分配下一个业务序号并追加事件。
   **/
  int append(
      @Param("taskId") String taskId,
      @Param("executionId") String executionId,
      @Param("eventType") String eventType,
      @Param("payload") JsonNode payload);

  /**
   * 查询排他游标之后的最多 500 条事件，按业务序号排序。
   **/
  List<EventRow> selectAfter(@Param("taskId") String taskId, @Param("after") long after);
}
