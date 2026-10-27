package com.aistarter.controller;

import com.aistarter.ai.ChatHistoryService;
import com.aistarter.ai.ChatService;
import com.aistarter.dto.ChatChunk;
import com.aistarter.dto.ChatRequest;
import com.aistarter.dto.SessionDtos;
import com.aistarter.observability.TraceFilter;
import com.aistarter.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 对话与会话入口。对话返回 SSE；其余接口都是普通 JSON。
 * 身份均从 SecurityContext 取（不信前端传的 userId），会话读写另做数据级属主校验。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatHistoryService historyService;

    /**
     * SSE 流式对话：首 token 延迟低，前端逐段渲染。
     * traceId 在此处取出并传入编排层，以保证日志/响应头/落库同源。
     * 护栏命中、会话不存在、越权等异常不会破流，而是包成 error 帧。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatChunk> stream(@Valid @RequestBody ChatRequest request) {
        var principal = SecurityUtils.requirePrincipal();
        return chatService.streamChat(request, principal, TraceFilter.currentTraceId());
    }

    /** 会话列表：ADMIN 看全量，USER 只看自己的，按 id 倒序 */
    @GetMapping("/sessions")
    public List<SessionDtos.SessionView> sessions() {
        var principal = SecurityUtils.requirePrincipal();
        return historyService.listSessions(principal).stream()
                .map(s -> new SessionDtos.SessionView(s.getId(), s.getTitle(), s.getPromptKey(),
                        s.getPromptVer(), s.getCreatedAt()))
                .toList();
    }

    /** 打开某个会话的历史消息（先过属主校验，最多取最近 200 条，目前不做分页） */
    @GetMapping("/sessions/{id}/messages")
    public List<SessionDtos.MessageView> messages(@PathVariable Long id) {
        var principal = SecurityUtils.requirePrincipal();
        historyService.requireOwned(id, principal);
        return historyService.recent(id, 200).stream()
                .map(m -> new SessionDtos.MessageView(m.getId(), m.getRole(), m.getContent(),
                        m.getTraceId(), m.getCreatedAt()))
                .toList();
    }

    /** 删除会话及其消息；非属主访问会抛 SecurityException → 403 */
    @DeleteMapping("/sessions/{id}")
    public void deleteSession(@PathVariable Long id) {
        historyService.deleteSession(id, SecurityUtils.requirePrincipal());
    }
}
