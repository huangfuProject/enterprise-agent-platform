package com.enterprise.platform;

import static org.assertj.core.api.Assertions.*;

import com.enterprise.platform.api.*;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.Validation;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Java 协议编码测试；生成夹具供 Python 集成测试做真实交叉校验。
 *
 * @author chomy
 **/
class ContractTest {
  /**
   * 验证 snake_case 和版本字段，确保 isFinite 校验辅助方法不会泄漏成协议属性。
   **/
  @Test
  void emitsVersionedRequestWithoutBeanHelperProperties() throws Exception {
    var request =
        new RuntimeRequest(
            "v1",
            "task-1",
            "exec-1",
            "key-1",
            "trace-1",
            "req-1",
            "task-1-exec-1",
            "main-agent",
            new TaskDtos.Input("contract", List.of(1d, 2d, 3d)),
            new TaskDtos.Limits(20, 120));
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(request)).isEmpty();
    }
    var mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    var tree = mapper.valueToTree(request);
    assertThat(tree.path("contract_version").asText()).isEqualTo("v1");
    assertThat(tree.path("input").has("finite")).isFalse();
    Files.createDirectories(Path.of("target/contract"));
    Files.writeString(
        Path.of("target/contract/java-request.json"), mapper.writeValueAsString(request));
  }
}
