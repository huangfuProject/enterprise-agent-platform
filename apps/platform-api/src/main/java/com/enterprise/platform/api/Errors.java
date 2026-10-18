package com.enterprise.platform.api;

import com.enterprise.platform.application.TaskException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/**
 * 公共 API 异常映射；只返回受控错误码与 trace_id。
 *
 * <p>不向调用方暴露 SQL、连接参数、请求原文或供应商异常消息。
 *
 * @author chomy
 **/
@RestControllerAdvice
public class Errors {
  /**
   * 构建有限的公共错误体，复用过滤器生成的关联 ID。
   **/
  private ResponseEntity<?> response(int status, String code, HttpServletRequest req) {
    return ResponseEntity.status(status)
        .body(Map.of("code", code, "trace_id", req.getAttribute("trace_id")));
  }

  /**
   * 返回受控业务错误，如404任务不存在、409幂等冲突。
   **/
  @ExceptionHandler(TaskException.class)
  ResponseEntity<?> task(TaskException e, HttpServletRequest req) {
    return response(e.status(), e.getMessage(), req);
  }

  /**
   * 统一处理 JSON 解析、DTO 约束和游标参数约束错误。
   **/
  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    jakarta.validation.ConstraintViolationException.class
  })
  ResponseEntity<?> invalid(Exception e, HttpServletRequest req) {
    return response(400, "INVALID_REQUEST", req);
  }

  /**
   * 数据库不可用时返回503，调用方可以明确区分基础设施失败。
   **/
  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> database(Exception e, HttpServletRequest req) {
    return response(503, "DATABASE_UNAVAILABLE", req);
  }

  /**
   * 未知异常失败关闭为500，避免把原始异常中的内部信息暴露给调用方。
   **/
  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e, HttpServletRequest req) {
    return response(500, "INTERNAL_ERROR", req);
  }
}
