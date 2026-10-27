package com.aistarter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 一条对话消息。多轮记忆与审计都靠这张表（而不是内存里的 ChatMemory），
 * 因此进程重启不丢、可按 traceId 回溯。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Entity
@Table(name = "chat_message")
@Getter
@Setter
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属会话；删会话时库层面 ON DELETE CASCADE 兼底 */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** user | assistant */
    @Column(nullable = false, length = 16)
    private String role;

    /** 对话正文：表结构用 TEXT，这里不加 @Lob（Hibernate 7 会把 @Lob String 映射成 PG large object） */
    @Column(nullable = false)
    private String content;

    /** 链路号，与日志/响应头/SSE 帧同源，页面上一处看到就能查到日志 */
    @Column(name = "trace_id", length = 32)
    private String traceId;

    /** 预留字段：当前未写入，token 口径统一走 ai.llm.tokens 指标 */
    @Column(name = "tokens")
    private Integer tokens;

    /** 消息落库时间（服务端时间）；历史排序实际按 id，不依赖这个字段 */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
