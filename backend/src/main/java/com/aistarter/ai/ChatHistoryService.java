package com.aistarter.ai;

import com.aistarter.domain.ChatMessage;
import com.aistarter.domain.ChatSession;
import com.aistarter.repository.ChatMessageRepository;
import com.aistarter.repository.ChatSessionRepository;
import com.aistarter.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 会话与多轮记忆存储：落在业务库 chat_session / chat_message，
 * 好处是记忆可审计、可回溯（traceId 关联日志与指标），也能被评测与看板复用。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    /**
     * 开一个新会话。标题取首问前 40 字，同时记下本轮用的 Prompt key/版本，便于事后归因。
     *
     * @param principal     当前登录用户
     * @param question      首个问题，仅用于生成标题
     * @param promptKey     系统提示词名，如 system-chat
     * @param promptVersion 提示词版本，如 v1/latest 解析后的值
     * @return 已落库的会话
     */
    @Transactional
    public ChatSession openSession(AuthPrincipal principal, String question, String promptKey, String promptVersion) {
        ChatSession session = new ChatSession();
        session.setUserId(principal.userId());
        session.setTitle(question.length() > 40 ? question.substring(0, 40) : question);
        session.setPromptKey(promptKey);
        session.setPromptVer(promptVersion);
        return sessionRepository.save(session);
    }

    /**
     * 校验会话存在且属于当前用户（ADMIN 可跨用户查看，用于运维场景）。
     *
     * @throws IllegalArgumentException 会话不存在
     * @throws SecurityException        越权访问他人会话（统一出口映射为 403）
     */
    public ChatSession requireOwned(Long sessionId, AuthPrincipal principal) {
        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
        if (!principal.isAdmin() && !session.getUserId().equals(principal.userId())) {
            throw new SecurityException("无权访问该会话");
        }
        return session;
    }

    /** 取最近 limit 条历史（倒序查出后重新按时间正序返回） */
    public List<ChatMessage> recent(Long sessionId, int limit) {
        List<ChatMessage> messages = messageRepository
                .findBySessionIdOrderByIdDesc(sessionId, PageRequest.of(0, limit));
        return messages.stream()
                .sorted(Comparator.comparing(ChatMessage::getId))
                .toList();
    }

    /** 把历史消息拼成可直接注入 Prompt 的纯文本；首轮无历史时给出明确标记 */
    public String renderHistory(Long sessionId, int limit) {
        List<ChatMessage> messages = recent(sessionId, limit);
        if (messages.isEmpty()) {
            return "（首轮对话，无历史）";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            sb.append("user".equals(m.getRole()) ? "用户: " : "助手: ").append(m.getContent()).append('\n');
        }
        return sb.toString();
    }

    /** 追加一条消息（role 取 user/assistant），traceId 与日志、指标同源 */
    @Transactional
    public ChatMessage append(Long sessionId, String role, String content, String traceId) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setTraceId(traceId);
        return messageRepository.save(message);
    }

    /** 会话列表：ADMIN 看全量，USER 只看自己的 */
    public List<ChatSession> listSessions(AuthPrincipal principal) {
        return principal.isAdmin()
                ? sessionRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))
                : sessionRepository.findByUserIdOrderByIdDesc(principal.userId());
    }

    /** 删除会话：先做属主校验，再清消息后删会话（消息表另有级联删除兜底） */
    @Transactional
    public void deleteSession(Long sessionId, AuthPrincipal principal) {
        requireOwned(sessionId, principal);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
    }
}
