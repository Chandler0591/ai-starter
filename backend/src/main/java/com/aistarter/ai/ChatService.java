package com.aistarter.ai;

import com.aistarter.ai.tool.ToolRegistry;
import com.aistarter.config.AiProperties;
import com.aistarter.domain.ChatSession;
import com.aistarter.dto.ChatChunk;
import com.aistarter.dto.ChatRequest;
import com.aistarter.observability.AiMetrics;
import com.aistarter.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 对话编排：护栏 -> 多轮记忆 -> RAG -> 工具 -> Prompt 组装 -> 流式模型调用 -> 降级 -> 落库。
 * 这一段是 AI 工程化的主战场：每个环节都可观测、可降级、可配置。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String SYSTEM_PROMPT = "system-chat";
    private static final String ANSWER_PROMPT = "rag-answer";
    private static final String FALLBACK_ANSWER = "服务繁忙，请稍后再试。";

    private final LlmClient llmClient;
    private final PromptRegistry promptRegistry;
    private final RagService ragService;
    private final GuardrailService guardrailService;
    private final ToolRegistry toolRegistry;
    private final ChatHistoryService historyService;
    private final AiMetrics metrics;
    private final AiProperties properties;

    /** 流式对话，返回 SSE 分片；会话与消息落库在流结束时完成 */
    public Flux<ChatChunk> streamChat(ChatRequest request, AuthPrincipal principal, String traceId) {
        return Flux.defer(() -> {
            guardrailService.checkInput(request.message());
            Prepared prepared = prepare(request, principal, traceId);
            String provider = llmClient.provider();
            StringBuilder answer = new StringBuilder();

            return llmClient.stream(prepared.llmRequest())
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .map(token -> {
                        answer.append(token);
                        return ChatChunk.token(token, traceId, prepared.sessionId(), provider);
                    })
                    .concatWith(Flux.just(ChatChunk.finish(traceId, prepared.sessionId(), provider)))
                    .doOnComplete(() -> persist(prepared.sessionId(), request.message(), answer.toString(), traceId))
                    .doOnError(e -> log.error("Chat stream failed, traceId={}", traceId, e))
                    .onErrorResume(e -> {
                        metrics.recordDegradation("llm");
                        return Flux.just(ChatChunk.failed(FALLBACK_ANSWER, traceId, prepared.sessionId(), provider));
                    });
        }).onErrorResume(GuardrailViolationException.class, e -> Flux.just(
                ChatChunk.failed(e.getMessage(), traceId, null, llmClient.provider())))
                // 兜底：会话不存在 / 越权访问他人会话 等编排前置阶段抛出的异常，
                // 统一回一帧可读错误，避免前端只看到一个直接断掉的流
                .onErrorResume(e -> {
                    log.warn("Chat prepare failed, traceId={}, cause={}", traceId, e.toString());
                    metrics.recordDegradation("prepare");
                    String message = e.getMessage() == null ? FALLBACK_ANSWER : e.getMessage();
                    return Flux.just(ChatChunk.failed(message, traceId, null, llmClient.provider()));
                });
    }

    /** 非流式：评测与批处理用。不落库，只做测量 */
    public String askOnce(ChatRequest request, AuthPrincipal principal, String traceId) {
        guardrailService.checkInput(request.message());
        return llmClient.complete(prepare(request, principal, traceId).llmRequest());
    }

    /**
     * 组装本轮模型请求：会话/历史/RAG/工具/Prompt 在此汇成一个不可变的 {@link LlmRequest}。
     * 任何一步抛异常都会由调用方的响应式 onErrorResume 转成一帧可读错误。
     */
    private Prepared prepare(ChatRequest request, AuthPrincipal principal, String traceId) {
        String promptVersion = request.promptVersion() == null ? "v1" : request.promptVersion();
        String systemPrompt = promptRegistry.get(SYSTEM_PROMPT, promptVersion);
        String answerTemplate = promptRegistry.get(ANSWER_PROMPT, "v1");

        // 1. 会话：无 sessionId 视为新会话
        ChatSession session = request.sessionId() == null
                ? historyService.openSession(principal, request.message(), SYSTEM_PROMPT, promptVersion)
                : historyService.requireOwned(request.sessionId(), principal);

        // 2. 多轮记忆：直接读业务库，可审计、与 traceId 关联
        String history = historyService.renderHistory(session.getId(), properties.getMaxHistory());

        // 3. RAG 检索（失败自动降级为空上下文）
        List<ContextSnippet> contexts = ragService.retrieve(request.message());

        // 4. 工具：服务端前置调用（意图命中）+ 交给模型的原生 function calling 工具集
        List<ToolRegistry.ToolOutcome> toolOutcomes = request.toolsEnabled()
                ? invokeTools(request.message(), principal)
                : List.of();
        List<Object> toolBeans = request.toolsEnabled()
                ? toolRegistry.toolsFor(principal.role())
                : List.of();
        List<String> toolNames = request.toolsEnabled()
                ? toolRegistry.toolNames(principal.role())
                : List.of();

        String userPrompt = PromptRegistry.render(answerTemplate, Map.of(
                "history", history,
                "context", renderContext(contexts),
                "tools", renderTools(toolOutcomes),
                "question", request.message()));

        LlmRequest llmRequest = new LlmRequest(traceId, systemPrompt, userPrompt,
                SYSTEM_PROMPT, promptVersion, contexts.size(), toolNames, toolBeans,
                Map.of("userId", principal.userId(), "admin", principal.isAdmin()));
        return new Prepared(session.getId(), llmRequest);
    }

    /** 服务端前置调用工具并记调用量（不依赖模型是否主动发起 function calling） */
    private List<ToolRegistry.ToolOutcome> invokeTools(String question, AuthPrincipal principal) {
        List<ToolRegistry.ToolOutcome> outcomes = toolRegistry.preInvoke(question, principal);
        outcomes.forEach(o -> metrics.recordToolCall(o.tool(), true));
        return outcomes;
    }

    /** 把命中片段渲染为「[n] 来源 + 相似度 + 正文」；无命中时给固定标记，供模板要求明确拒答 */
    private String renderContext(List<ContextSnippet> contexts) {
        if (contexts.isEmpty()) {
            return "（无命中片段）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contexts.size(); i++) {
            ContextSnippet c = contexts.get(i);
            sb.append('[').append(i + 1).append("] 来源：").append(c.title())
                    .append(String.format(" 相似度 %.3f%n", c.score()))
                    .append(c.text()).append("\n---\n");
        }
        return sb.toString();
    }

    /** 把工具返回结果渲染为提示词片段 */
    private String renderTools(List<ToolRegistry.ToolOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return "（本轮服务端未预先调用工具；如需可自行发起 function calling）";
        }
        StringBuilder sb = new StringBuilder();
        outcomes.forEach(o -> sb.append('#').append(o.tool()).append(" => ").append(o.output()).append('\n'));
        return sb.toString();
    }

    /** 流结束后一次性落库 user + assistant；空回答不写 */
    private void persist(Long sessionId, String question, String answer, String traceId) {
        if (answer.isBlank()) {
            return;
        }
        try {
            historyService.append(sessionId, "user", question, traceId);
            historyService.append(sessionId, "assistant", answer, traceId);
        } catch (Exception e) {
            // 落库失败不影响已推送给用户的回答，只记日志与指标
            log.warn("Persist chat messages failed, traceId={}, cause={}", traceId, e.getMessage());
            metrics.recordDegradation("persist");
        }
    }

    /** 编排产物：会话 id + 最终交给模型层的一次性请求 */
    private record Prepared(Long sessionId, LlmRequest llmRequest) {
    }
}
