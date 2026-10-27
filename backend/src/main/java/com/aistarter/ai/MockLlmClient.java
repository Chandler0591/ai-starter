package com.aistarter.ai;

import com.aistarter.config.AiProperties;
import com.aistarter.observability.AiMetrics;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Mock Provider：不调用任何外部服务，输出完全确定。
 * 用途——1) 无 API Key 也能跑通「护栏→RAG→工具→Prompt→流式→落库→指标」全链路；
 * 2) 让离线评测可复现（EvalRunner 默认基于本实现做回归断言）。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Slf4j
public class MockLlmClient implements LlmClient {

    /** 每帧下发的字符数，用于模拟流式逐段输出 */
    private static final int CHUNK_CHARS = 12;

    private final AiMetrics metrics;
    /** 模拟延迟（ai.mock.latency-ms），让 mock 下的耗时分位数也是真实量级 */
    private final long simulatedLatencyMs;

    /**
     * @param metrics    指标出口，mock 也要打点
     * @param properties 读取 {@code ai.mock.latency-ms}
     */
    public MockLlmClient(AiMetrics metrics, AiProperties properties) {
        this.metrics = metrics;
        this.simulatedLatencyMs = properties.getMock().getLatencyMs();
    }

    /** provider 标识固定为 mock，作为指标 tag 与回答溯源 */
    @Override
    public String provider() {
        return "mock";
    }

    /** 非流式：直接拼出确定回答并记调用量，评测与批处理走这条路 */
    @Override
    public String complete(LlmRequest request) {
        long start = System.currentTimeMillis();
        String answer = buildAnswer(request);
        recordCall("complete", request, answer, start);
        return answer;
    }

    /**
     * 流式：把确定回答按 {@code CHUNK_CHARS} 分片下发，每片间隔 15ms。
     * 用 Flux.defer 保证每次订阅重新计时，并在流收尾时记一次调用量与 token。
     */
    @Override
    public Flux<String> stream(LlmRequest request) {
        List<String> parts = slice(buildAnswer(request));
        return Flux.defer(() -> {
            long start = System.currentTimeMillis();
            String full = String.join("", parts);
            return Flux.fromIterable(parts)
                    .delayElements(Duration.ofMillis(15))
                    .doOnComplete(() -> recordCall("stream", request, full, start));
        });
    }

    /** mock 模式也要有 llm 指标，否则看板与告警在离线演示时是空的 */
    private void recordCall(String type, LlmRequest request, String answer, long start) {
        long ms = Math.max(simulatedLatencyMs, System.currentTimeMillis() - start);
        metrics.recordLlmCall(type, provider(), ms, true);
        metrics.recordTokens(provider(), "prompt", estimateTokens(request.systemPrompt() + request.userPrompt()));
        metrics.recordTokens(provider(), "completion", estimateTokens(answer));
    }

    /** 中文按 1 token ≈ 1.5 字符估算，只用于指标口径展示，不做计费依据 */
    private long estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, (long) (text.length() / 1.5));
    }

    /** 根据请求里的统计字段拼出确定文本，既能验链路通，也让评测断言不会脆断 */
    private String buildAnswer(LlmRequest request) {
        String ragVerdict = request.contextCount() > 0
                ? "已依据知识库片段作答"
                : "知识库中未找到相关信息";
        String tools = request.toolNames().isEmpty() ? "无" : String.join(", ", request.toolNames());
        return """
                [MOCK 回答] provider=mock, traceId=%s
                - Prompt：%s@%s
                - 知识库命中：%d 段 → %s
                - 本轮可用工具：%s
                - 用户输入长度：%d 字符
                （切换 AI_PROVIDER=openai 并配置 OPENAI_API_KEY 后，本段将由真实模型生成）"""
                .formatted(request.traceId(), request.promptKey(), request.promptVersion(),
                        request.contextCount(), ragVerdict, tools, request.userPrompt().length());
    }

    /** 按固定字符数切片，模拟真模型的逐 token 输出 */
    private List<String> slice(String text) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += CHUNK_CHARS) {
            parts.add(text.substring(i, Math.min(text.length(), i + CHUNK_CHARS)));
        }
        return parts;
    }
}
