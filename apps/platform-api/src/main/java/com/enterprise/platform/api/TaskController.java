package com.enterprise.platform.api;

import com.enterprise.platform.application.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * P0 公共任务协议层：校验请求并转换稳定的响应 DTO。
 *
 * <p>业务幂等、取消和持久化交给 Service/Repository；此处未实现身份授权。
 *
 * @author chomy
 **/
@RestController
@RequestMapping("/api/v1/tasks")
@Validated
public class TaskController {
  private final TaskService service;

  /**
   * 注入应用服务，保持协议层不承载状态编排。
   **/
  public TaskController(TaskService service) {
    this.service = service;
  }

  /**
   * 提交或幂等获取任务。
   *
   * @param body 经过嵌套 Bean Validation 的请求
   * @param req 已由 TraceFilter 写入 trace_id 的 HTTP 请求
   * @return 202 对应的任务投影；不表示 Agent 已完成
   **/
  @PostMapping
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public Object create(@Valid @RequestBody TaskDtos.CreateTask body, HttpServletRequest req) {
    return TaskView.from(service.create(body, (String) req.getAttribute("trace_id")));
  }

  /**
   * 按业务任务标识查询状态和结果；不存在的任务由统一异常层映射404。
   **/
  @GetMapping("/{id}")
  public Object get(@PathVariable String id) {
    return TaskView.from(service.get(id));
  }

  /**
   * 读取业务序号严格大于 after 的一页事件，游标与 Runtime 原始序号不同。
   **/
  @GetMapping("/{id}/events")
  public Object events(
      @PathVariable String id, @RequestParam(defaultValue = "0") @Min(0) long after) {
    return service.events(id, after);
  }

  /**
   * 幂等请求取消；已经终态的任务保持原结果，不承诺瞬间冻结执行。
   **/
  @PostMapping("/{id}/cancel")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public Object cancel(@PathVariable String id) {
    return TaskView.from(service.cancel(id));
  }
}
