package com.enterprise.platform.infrastructure;

import com.enterprise.platform.application.*;
import com.enterprise.platform.domain.TaskRecord;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * TaskExecutionEngine 的 HTTP v1 适配器。
 *
 * <p>连接与请求均有界；传输失败表示执行结果未知，由 Dispatcher 后续查询，不能直接新建 Run。
 *
 * @author chomy
 **/
@Component
public class HttpTaskExecutionEngine implements TaskExecutionEngine {
  private final HttpClient client;
  private final ObjectMapper json;
  private final String base;
  private final Duration timeout;

  /**
   * 配置有界 HTTP 客户端；HTTP/1.1 避免对当前 Uvicorn 发送不必要的 h2c 升级请求。
   **/
  public HttpTaskExecutionEngine(
      ObjectMapper json,
      @Value("${p0.runtime-url}") String base,
      @Value("${p0.http-timeout-ms}") long timeout) {
    this.json = json;
    this.base = base;
    this.timeout = Duration.ofMillis(timeout);
    client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();
  }

  /**
   * 发送一次有界 HTTP JSON 请求并映射安全传输错误。
   *
   * <p>不做隐式重试；结果未知时交由 Dispatcher 使用原执行身份继续查询。
   **/
  private JsonNode call(String path, String method, Object body) {
    try {
      var builder =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(timeout)
              .header("Content-Type", "application/json");
      // 扩展引擎也不能跳过 DTO 投影，避免未来领域字段被意外暴露给 Runtime。
      if (body instanceof TaskRecord)
        throw new IllegalArgumentException("Internal state cannot be serialized as contract");
      builder.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) throw new TaskException(404, "EXECUTION_NOT_FOUND");
      if (response.statusCode() / 100 != 2) throw new TaskException(503, "RUNTIME_UNAVAILABLE");
      return json.readTree(response.body());
    } catch (InterruptedException e) {
      // 保留线程中断语义，避免调度器关闭信号被异常映射吞掉。
      Thread.currentThread().interrupt();
      throw new TaskException(503, "RUNTIME_INTERRUPTED");
    } catch (TaskException e) {
      throw e;
    } catch (Exception e) {
      // 不回传原始网络异常文本，其中可能包含内部端点或其他敏感诊断。
      throw new TaskException(503, "RUNTIME_OUTCOME_UNKNOWN");
    }
  }

  /**
   * 将内部 TaskRecord 投影为独立 RuntimeRequest 并校验，禁止内部状态结构泄漏到协议。
   **/
  @Override
  public void submit(TaskRecord t) {
    try {
      var request =
          new com.enterprise.platform.api.RuntimeRequest(
              "v1",
              t.taskId(),
              t.executionId(),
              t.idempotencyKey(),
              t.traceId(),
              t.requestId(),
              t.checkpointThreadId(),
              "main-agent",
              json.treeToValue(t.input(), com.enterprise.platform.api.TaskDtos.Input.class),
              json.treeToValue(t.limits(), com.enterprise.platform.api.TaskDtos.Limits.class));
      try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
        if (!factory.getValidator().validate(request).isEmpty())
          throw new IllegalStateException("INVALID_RUNTIME_REQUEST");
      }
      call("/internal/v1/executions", "POST", request);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("INVALID_TASK_PAYLOAD", e);
    }
  }

  /**
   * 获取当前执行快照；返回的是 Runtime 状态，不直接修改 Java Task。
   **/
  @Override
  public JsonNode getStatus(String id) {
    return call("/internal/v1/executions/" + id, "GET", null);
  }

  /**
   * 从最后已导入游标读取运行内增量事件；业务序号由 Repository 重新分配。
   **/
  @Override
  public JsonNode getEvents(String id, long after) {
    return call("/internal/v1/executions/" + id + "/events?after=" + after, "GET", null);
  }

  /**
   * 向 Runtime 提交幂等取消意图；具体进程终止由 Runtime 监督。
   **/
  @Override
  public void cancel(String id) {
    call("/internal/v1/executions/" + id + "/cancel", "POST", null);
  }
}
