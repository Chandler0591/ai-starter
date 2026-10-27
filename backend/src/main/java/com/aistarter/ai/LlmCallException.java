package com.aistarter.ai;

/**
 * 模型侧异常的统一包装，便于编排层做降级与指标分类。
 * HTTP 层映射为 502（上游错误）；流式对话则转成兜底回答帧。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public class LlmCallException extends RuntimeException {

    /**
     * @param message 可读错误说明（只入日志，不直接外抛给用户）
     * @param cause   原始异常，保留堆栈便于定位
     */
    public LlmCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
