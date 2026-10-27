package com.aistarter.ai;

import reactor.core.publisher.Flux;

/**
 * LLM 抽象层：业务代码只依赖此接口。
 * 好处——换厂商不改业务、可 Mock 单测、可注入降级与指标逻辑。
 * 实现由 {@code AiConfig} 按 {@code ai.provider} 装配（mock / openai）。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public interface LlmClient {

    /** provider 标识，用于指标 tag 与回答溯源 */
    String provider();

    /** 非流式，评测/批处理场景使用 */
    String complete(LlmRequest request);

    /** 流式，SSE 逐 token 输出 */
    Flux<String> stream(LlmRequest request);
}
