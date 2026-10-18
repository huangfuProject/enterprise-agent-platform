package com.enterprise.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Run 表访问：普通插入/更新使用 BaseMapper，租约与 fencing 使用专用 SQL。
 *
 * @author chomy
 **/
@Mapper
public interface RunMapper extends BaseMapper<RunEntity> {
  /**
   * 领取后绑定租约；只在调用方已取得任务分布式锁并完成版本 CAS 的事务中使用。
   **/
  int claim(
      @Param("executionId") String executionId,
      @Param("owner") String owner,
      @Param("leaseSeconds") int leaseSeconds);

  /**
   * 按数据库时钟确认请求发出时的持有者代数仍然有效。
   **/
  int countValidLease(
      @Param("executionId") String executionId,
      @Param("fence") long fence,
      @Param("owner") String owner);

  /**
   * 失联时提高 fence 并记录安全失败原因，不自动重新排队。
   **/
  int expire(@Param("executionId") String executionId, @Param("status") String status);

  /**
   * 原子更新 Runtime 快照、事件游标及租约；空结果必须能够覆盖数据库中的旧值。
   **/
  int reconcile(
      @Param("run") RunEntity run,
      @Param("startedAt") String startedAt,
      @Param("leaseSeconds") int leaseSeconds,
      @Param("fence") long fence,
      @Param("owner") String owner);
}
