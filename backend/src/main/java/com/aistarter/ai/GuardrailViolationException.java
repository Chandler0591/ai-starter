package com.aistarter.ai;

/**
 * 输入被护栏拦截。HTTP 层映射为 400；流式对话则转成一帧 error 返回，不产生模型调用。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public class GuardrailViolationException extends RuntimeException {

    /** 拦截原因：EMPTY / TOO_LONG / INJECTION，作为指标 tag */
    private final String reason;

    /**
     * @param reason  拦截原因分类
     * @param message 返回给用户的可读文案
     */
    public GuardrailViolationException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** 拦截原因，用于指标维度与问题定位 */
    public String reason() {
        return reason;
    }
}
