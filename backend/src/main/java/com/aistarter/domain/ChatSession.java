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
 * 一次多轮会话；promptKey/promptVer 记录本次会话使用的 Prompt 版本，便于 A-B 对比。
 * 属主只映射为 user_id 字段（不建 JPA 关联对象，库层面仍有 REFERENCES 约束），
 * 越权判断由 ChatHistoryService 显式完成。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Entity
@Table(name = "chat_session")
@Getter
@Setter
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话属主；USER 只能读写自己的会话，ADMIN 可跨用户查看 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    private String title;

    @Column(name = "prompt_key", length = 64)
    private String promptKey;

    @Column(name = "prompt_ver", length = 16)
    private String promptVer;

    /** 建会话的时间（接口里回给前端展示；列表排序实际走 id 倒序，不依赖这个字段） */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
