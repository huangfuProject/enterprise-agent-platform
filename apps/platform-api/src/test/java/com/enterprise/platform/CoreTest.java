package com.enterprise.platform;

import static org.assertj.core.api.Assertions.*;

import com.enterprise.platform.api.TaskDtos.*;
import com.enterprise.platform.domain.TaskState;
import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 确定性 DTO 与状态机测试，不依赖数据库和外部模型。 */
class CoreTest {
  /** 验证嵌套 DTO、预算、空消息/空集合与非有限数值约束。 */
  @Test
  void validatesInputAndLimits() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertThat(
              validator.validate(
                  new CreateTask("key", new Input("hello", List.of(1d)), new Limits(20, 120))))
          .isEmpty();
      assertThat(
              validator.validate(new CreateTask("", new Input("", List.of()), new Limits(0, 400))))
          .hasSize(5);
      assertThat(validator.validate(new Input("x", List.of(Double.NaN)))).isNotEmpty();
    }
  }

  /** 断言非法回退、取消后的成功覆盖及任何终态迁移均被拒绝。 */
  @Test
  void guardsTransitionsAndTerminalStates() {
    assertThat(TaskState.QUEUED.allows(TaskState.CLAIMED)).isTrue();
    assertThat(TaskState.CANCEL_REQUESTED.allows(TaskState.SUCCEEDED)).isFalse();
    assertThat(TaskState.RUNNING.allows(TaskState.QUEUED)).isFalse();
    for (var state : List.of(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED)) {
      assertThat(state.terminal()).isTrue();
      for (var target : TaskState.values()) assertThat(state.allows(target)).isFalse();
    }
  }
}
