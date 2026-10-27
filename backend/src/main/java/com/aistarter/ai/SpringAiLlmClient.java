package com.aistarter.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import com.aistarter.observability.AiMetrics;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI 兼容 Provider（Spring AI 2.0）：
 * base-url 指向任意兼容网关即可切换 DeepSeek / 通义 / 自建服务，业务代码零改动。
 * 工具通过 @Tool 注解对象传入，由 ToolCallingAdvisor 执行原生 function calling 循环。
 * <p>注意：本分支需真实 API Key 才能跑通，尚未在在线网关上实测（详见 docs/TEST-PLAN.md 残留风险）。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Slf4j
public class SpringAiLlmClient implements LlmClient {

    private final ChatClient chatClient;
    private final AiMetrics metrics;
    private final Duration timeout;

    /**
     * @param chatClient 由 ChatClient.Builder 构建（已挂 ToolCallingAdvisor）
     * @param metrics    调用量、耗时与 token 打点
     * @param timeoutMs  流式整体超时，来自 ai.timeout-ms
     */
    public SpringAiLlmClient(ChatClient chatClient, AiMetrics metrics, long timeoutMs) {
        this.chatClient = chatClient;
        this.metrics = metrics;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /** provider 标识固定为 openai（指兼容网关，不限于 OpenAI 官方） */
    @Override
    public String provider() {
        return "openai";
    }

    /**
     * 非流式调用：取回完整回答，同时记 token 用量与调用结果；
     * 异常统一包成 {@link LlmCallException}（HTTP 映射 502），失败也要打点，否则成功率假高。
     */
    @Override
    public String complete(LlmRequest request) {
        long start = System.currentTimeMillis();
        try {
            ChatResponse response = prepare(request).call().chatResponse();
            String text = response == null || response.getResult() == null
                    ? "" : response.getResult().getOutput().getText();
            recordUsage(response);
            metrics.recordLlmCall("complete", provider(), System.currentTimeMillis() - start, true);
            return text;
        } catch (Exception e) {
            metrics.recordLlmCall("complete", provider(), System.currentTimeMillis() - start, false);
            log.error("LLM complete failed, traceId={}", request.traceId(), e);
            throw new LlmCallException("模型调用失败", e);
        }
    }

    /**
     * 流式调用：逐分片输出文本，整段受 {@code timeout} 约束。
     * 用最后一个 ChatResponse 取 usage（尾帧才带完整 token 统计），收尾时一并记录调用量。
     */
    @Override
    public Flux<String> stream(LlmRequest request) {
        long start = System.currentTimeMillis();
        AtomicReference<ChatResponse> last = new AtomicReference<>();
        return prepare(request)
                .stream()
                .chatResponse()
                .doOnNext(last::set)
                .mapNotNull(resp -> resp.getResult() == null ? null : resp.getResult().getOutput().getText())
                .timeout(timeout)
                .doOnComplete(() -> {
                    recordUsage(last.get());
                    metrics.recordLlmCall("stream", provider(), System.currentTimeMillis() - start, true);
                })
                .doOnError(e -> {
                    metrics.recordLlmCall("stream", provider(), System.currentTimeMillis() - start, false);
                    log.error("LLM stream failed, traceId={}", request.traceId(), e);
                });
    }

    /** 把统一入参落到 ChatClient 请求链上：系统/用户 Prompt + 工具对象 + 工具上下文 */
    private ChatClient.ChatClientRequestSpec prepare(LlmRequest request) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .system(request.systemPrompt())
                .user(request.userPrompt());
        if (!request.toolBeans().isEmpty()) {
            spec = spec.tools(request.toolBeans().toArray());
        }
        if (!request.toolContext().isEmpty()) {
            spec = spec.toolContext(request.toolContext());
        }
        return spec;
    }

    /** 从响应元数据里取 prompt/completion token 写入指标；部分网关不回 usage，为空则跳过 */
    private void recordUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return;
        }
        var usage = response.getMetadata().getUsage();
        if (usage.getPromptTokens() != null) {
            metrics.recordTokens(provider(), "prompt", usage.getPromptTokens());
        }
        if (usage.getCompletionTokens() != null) {
            metrics.recordTokens(provider(), "completion", usage.getCompletionTokens());
        }
    }
}
