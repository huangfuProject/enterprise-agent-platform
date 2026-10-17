package com.enterprise.platform.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每次公共 HTTP 请求建立可关联的 trace_id。
 *
 * <p>限制外部标识字符和长度，避免日志注入；线程结束时必须清理 MDC，防止线程池复用串号。
 */
@Component
public class TraceFilter extends OncePerRequestFilter {
  /** 校验或生成 trace，写入请求、响应和 MDC，并在 finally 中清理线程上下文。 */
  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var supplied = req.getHeader("X-Trace-Id");
    var trace =
        supplied != null && supplied.matches("[A-Za-z0-9_-]{1,80}")
            ? supplied
            : UUID.randomUUID().toString();
    req.setAttribute("trace_id", trace);
    res.setHeader("X-Trace-Id", trace);
    MDC.put("trace_id", trace);
    try {
      chain.doFilter(req, res);
    } finally {
      // Servlet 线程会复用；必须清理，后一个请求不能继承前一个请求的关联 ID。
      MDC.remove("trace_id");
    }
  }
}
