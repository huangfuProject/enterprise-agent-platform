package com.enterprise.platform.application;

import com.enterprise.platform.api.TaskDtos.CreateTask;
import com.enterprise.platform.domain.TaskRecord;
import com.enterprise.platform.infrastructure.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 任务应用服务：计算稳定请求指纹，委托 Repository 执行业务事务。
 *
 * <p>不在创建请求中同步执行 Agent，数据库事务也不跨越模型或 HTTP 调用。
 *
 * @author chomy
 **/
@Service
public class TaskService {
  private final TaskRepository repository;
  private final ObjectMapper json;

  /**
   * 注入持久化边界与统一 JSON 映射器，保证指纹和实际 DTO 编码规则一致。
   **/
  public TaskService(TaskRepository repository, ObjectMapper json) {
    this.repository = repository;
    this.json = json;
  }

  /**
   * 基于规范化 DTO 生成 SHA-256 指纹，并原子创建 Task/Run。
   *
   * @param body 已通过公共入口校验的输入
   * @param trace 当前请求的关联 ID；重复创建仍返回原任务的 trace
   * @return 新建或已存在的同一业务任务
   * @throws TaskException 同键异载荷时返回409
   **/
  public TaskRecord create(CreateTask body, String trace) {
    try {
      // 先经过 DTO 规范化再编码，JSON 键顺序/数值文本差异不会绕过既定对象编码。
      var payload = json.writeValueAsBytes(body);
      var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
      return repository.create(body, hash, trace);
    } catch (TaskException e) {
      throw e;
    } catch (Exception e) {
      if (e instanceof RuntimeException r) throw r;
      throw new IllegalStateException(e);
    }
  }

  /**
   * 查询任务快照；此读取不隐式触发执行或恢复。
   **/
  public TaskRecord get(String id) {
    return repository.get(id);
  }

  /**
   * 委托受控状态迁移处理取消，与完成竞争由数据库事务决定顺序。
   **/
  public TaskRecord cancel(String id) {
    return repository.cancel(id);
  }

  /**
   * 读取一页业务事件，以任务内 sequence 作为排他游标。
   **/
  public List<Map<String, Object>> events(String id, long after) {
    return repository.events(id, after);
  }
}
