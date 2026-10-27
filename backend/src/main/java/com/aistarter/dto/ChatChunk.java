package com.aistarter.dto;

/**
 * SSE 数据帧：前端逐 token 渲染，done=true 表示本轮结束。
 * 错误也按同一帧结构下发（error=true + content 为可读文案），HTTP 仍为 200，
 * 客户端不会看到一个莫名断掉的流。
 *
 * @param content   本帧增量文本（错误帧下为错误文案）
 * @param done      是否结束帧，前端据此收束读取循环
 * @param error     本帧内容是否为错误提示
 * @param traceId   链路号，与日志/响应头/落库同源
 * @param sessionId 服务端确定的会话 id，下一轮请求带回即可续用
 * @param provider  当前模型提供方（mock / openai）
 * @author Flynn
 * @date 2026-10-27
 */
public record ChatChunk(String content, boolean done, boolean error, String traceId, Long sessionId, String provider) {

    /** 普通增量帧 */
    public static ChatChunk token(String content, String traceId, Long sessionId, String provider) {
        return new ChatChunk(content, false, false, traceId, sessionId, provider);
    }

    /** 结束帧（内容为空） */
    public static ChatChunk finish(String traceId, Long sessionId, String provider) {
        return new ChatChunk("", true, false, traceId, sessionId, provider);
    }

    /** 错误结束帧：护栏拦截、越权、模型降级兜底等都用它 */
    public static ChatChunk failed(String message, String traceId, Long sessionId, String provider) {
        return new ChatChunk(message, true, true, traceId, sessionId, provider);
    }
}
