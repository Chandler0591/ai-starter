package com.aistarter.observability;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId 透传：请求级生成 -> MDC（日志可检索）-> 响应头回传前端。
 * 前端把 X-Trace-Id 显示在对话气泡上，出问题时可直接按 traceId 捞日志与指标。
 * 排在最高优先级，让包括安全链在内的所有日志都带上 traceId。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter implements Filter {

    /** MDC 里的 key，日志 pattern 以 %X{traceId:-} 取用（见 application.yml 的 logging.pattern.console） */
    public static final String TRACE_ID = "traceId";
    /** 进出的 HTTP 头名，前端与后端共用这一个 */
    public static final String HEADER = "X-Trace-Id";

    /** 当前请求的 traceId；不在请求线程上（如启动期的 Seeder）返回 "-"，避免日志拼接时抛空指针 */
    public static String currentTraceId() {
        String id = MDC.get(TRACE_ID);
        return id != null ? id : "-";
    }

    /**
     * 优先沿用调用方传来的 traceId（便于串联上游链路），但只接受长度不超过 32 的短值，
     * 避免恶意或超长头部把垃圾数据写进日志；不合法则自己生成 12 位短 id。
     * finally 里必须清理 MDC，否则线程复用时下一个请求会拿到上一个 traceId。
     */
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        String incoming = req instanceof HttpServletRequest http ? http.getHeader(HEADER) : null;
        String traceId = (incoming != null && !incoming.isBlank() && incoming.length() <= 32)
                ? incoming
                : UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MDC.put(TRACE_ID, traceId);
        if (res instanceof HttpServletResponse http) {
            http.setHeader(HEADER, traceId);
        }
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}
