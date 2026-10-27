package com.aistarter.controller;

import com.aistarter.ai.GuardrailViolationException;
import com.aistarter.ai.LlmCallException;
import com.aistarter.observability.TraceFilter;
import com.aistarter.security.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 统一异常出口：错误响应带 traceId，前端可直接按 traceId 关联日志与指标。
 * 只回可读文案，堆栈仅入日志；SSE 接口内的异常不走这里（已包装成 error 帧）。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 统一错误体。
     *
     * @param status  HTTP 状态码
     * @param message 可读文案，为空时回落为状态码原因短语
     * @param traceId 链路号，与响应头 X-Trace-Id 同源
     */
    public record ErrorResponse(int status, String message, String traceId) {
    }

    /** 护栏拦截（仅非流式接口会到这里）→ 400 */
    @ExceptionHandler(GuardrailViolationException.class)
    public ResponseEntity<ErrorResponse> guardrail(GuardrailViolationException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Bean Validation 失败 → 400，按「field: 消息」拼接全部字段错误 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message);
    }

    /** 业务参数错误（如文档不存在、Prompt 版本不存在）→ 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 账号或口令不对 → 401 */
    @ExceptionHandler(AuthService.BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> unauthorized(AuthService.BadCredentialsException e) {
        return build(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    /** 越权（含会话属主校验失败、工具数据越权）→ 403 */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> forbidden(SecurityException e) {
        return build(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /** 模型网关异常 → 502，区分于本服务自身故障，便于告警分流 */
    @ExceptionHandler(LlmCallException.class)
    public ResponseEntity<ErrorResponse> upstream(LlmCallException e) {
        return build(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    /** 统一构造错误体：traceId 取自当前线程 MDC（由 TraceFilter 写入） */
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), message == null ? status.getReasonPhrase() : message,
                        TraceFilter.currentTraceId()));
    }
}
