package com.enterprise.platform;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 仅在测试 classpath 中注册的故障注入 Mapper，不作为生产持久化接口发布。
 *
 * @author chomy
 **/
@Mapper
public interface PostgresFixtureMapper {
  /**
   * 只使指定测试任务租约过期，不依赖真实等待或清理其他任务。
   **/
  @Update(
      "UPDATE p0_run SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE task_id=#{taskId}")
  int expireLease(@Param("taskId") String taskId);
}
