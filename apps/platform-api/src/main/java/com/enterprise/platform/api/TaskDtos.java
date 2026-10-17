package com.enterprise.platform.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/**
 * 公共任务请求 DTO 集合，与数据库记录和 Runtime 内部状态分离。
 *
 * <p>字段名经全局 Jackson snake_case 配置映射，约束应与版本化契约保持一致。
 */
public final class TaskDtos {
  /** 只作为 DTO 命名空间，禁止创建无意义的容器实例。 */
  private TaskDtos() {}

  /**
   * 有限任务输入。
   *
   * <p>关闭 isGetter 自动发现，避免 isFinite 辅助校验方法生成额外 finite 协议字段。
   *
   * @param message 用户目标文本，非空且有长度上限
   * @param values 有限数值样例，不是任意数据源或表达式
   */
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
  public record Input(
      @NotBlank @Size(max = 4000) String message,
      @NotNull @Size(min = 1, max = 100) List<@NotNull Double> values) {
    /** 拒绝 NaN/Infinity；null 与元素空值由各自的 NotNull 约束报告。 */
    @AssertTrue(message = "values must be finite")
    public boolean isFinite() {
      return values == null || values.stream().allMatch(v -> v == null || Double.isFinite(v));
    }
  }

  /** 执行上限 DTO；步骤由主/子共享，总超时由独立执行进程监督。 */
  public record Limits(@Min(1) @Max(100) int maxSteps, @Min(1) @Max(300) int timeoutSeconds) {}

  /** 创建任务请求；相同幂等键必须对应同一个规范化 DTO 载荷。 */
  public record CreateTask(
      @NotBlank @Size(max = 100) String idempotencyKey,
      @NotNull @Valid Input input,
      @NotNull @Valid Limits limits) {}
}
