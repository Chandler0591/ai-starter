package com.aistarter.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * AI 调用可观测性：延迟 / 成功率 / token 用量 / RAG 命中 / 工具调用 / 护栏拦截。
 * 指标通过 /actuator/prometheus 暴露，可被 Prometheus 抓取；
 * 同时 DashboardController 直接读 MeterRegistry，省去查询语言依赖。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Component
@RequiredArgsConstructor
public class AiMetrics {

    private final MeterRegistry registry;

    /**
     * 记一次模型调用：同时落耗时与次数，success 作为 tag 便于直接算成功率。
     *
     * @param type     调用类型：complete / stream
     * @param provider 模型提供方名称
     * @param ms       本次调用耗时（毫秒）
     * @param success  是否成功；失败也记录，不要用异常吞掉指标
     */
    public void recordLlmCall(String type, String provider, long ms, boolean success) {
        Timer.builder("ai.llm.latency")
                .description("LLM 调用耗时")
                .tag("type", type)
                .tag("provider", provider)
                .tag("success", String.valueOf(success))
                .register(registry)
                .record(ms, TimeUnit.MILLISECONDS);

        Counter.builder("ai.llm.calls")
                .description("LLM 调用次数")
                .tag("type", type)
                .tag("provider", provider)
                .tag("success", String.valueOf(success))
                .register(registry)
                .increment();
    }

    /**
     * 记 token 用量。
     *
     * @param kind prompt / completion；mock provider 下为估算值，仅供量级参考
     */
    public void recordTokens(String provider, String kind, long tokens) {
        Counter.builder("ai.llm.tokens")
                .description("token 用量")
                .tag("provider", provider)
                .tag("kind", kind)
                .register(registry)
                .increment(tokens);
    }

    /**
     * 记一次检索：context 数用 summary 统计分布（看平均命中几条），耗时用 Timer。
     *
     * @param hitCount 本次注入 Prompt 的片段数，0 代表未命中
     * @param ms       检索耗时（毫秒）
     */
    public void recordRag(int hitCount, long ms) {
        Timer.builder("ai.rag.latency").register(registry).record(ms, TimeUnit.MILLISECONDS);
        registry.summary("ai.rag.contexts").record(hitCount);
    }

    /**
     * 记一次工具调用。
     *
     * @param success 调用是否成功；失败也应当打点，否则只看得到“调了”看不到“调挂了”
     */
    public void recordToolCall(String tool, boolean success) {
        Counter.builder("ai.tool.calls")
                .tag("tool", tool)
                .tag("success", String.valueOf(success))
                .register(registry)
                .increment();
    }

    /**
     * 记一次护栏拦截。
     *
     * @param reason EMPTY / TOO_LONG / INJECTION，用于区分是坏输入还是攻击尝试
     */
    public void recordGuardrailBlock(String reason) {
        Counter.builder("ai.guardrail.blocked")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /**
     * 记一次降级或前置失败。
     *
     * @param stage rag / llm / persist / prepare，分别对应检索、模型、落库、编排前置
     */
    public void recordDegradation(String stage) {
        Counter.builder("ai.degradation")
                .tag("stage", stage)
                .register(registry)
                .increment();
    }
}
