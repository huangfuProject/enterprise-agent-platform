package com.enterprise.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.enterprise.platform.domain.TaskRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Task 表访问：普通查询使用 BaseMapper，领取及状态更新由 XML 保留明确的 PostgreSQL 语义。
 *
 * @author chomy
 **/
@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {
  /**
   * 联查 Task/Run 为内部快照，保持一次查询的一致视图。
   **/
  TaskRecord selectSnapshot(@Param("taskId") String taskId);

  /**
   * 使用唯一约束及 ON CONFLICT 原子创建任务，返回实际插入行数。
   **/
  int insertIfAbsent(@Param("task") TaskEntity task);

  /**
   * 读取最多32个排队候选，调用方通过任务级分布式锁竞争。
   **/
  List<String> selectQueuedCandidates();

  /**
   * 按期望版本和原状态条件更新；影响行数必须为1，否则调用方整体回滚。
   **/
  int compareAndSetState(
      @Param("taskId") String taskId,
      @Param("version") long version,
      @Param("expectedState") String expectedState,
      @Param("state") String state);

  /**
   * 查询当前实例仍具有有效租约的最多四个活动任务。
   **/
  List<TaskRecord> selectOwned(@Param("owner") String owner);

  /**
   * 读取最多100个到期候选；锁内必须再次验证状态及数据库租约。
   **/
  List<TaskRecord> selectExpiredCandidates();
}
