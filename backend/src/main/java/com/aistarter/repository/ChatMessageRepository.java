package com.aistarter.repository;

import com.aistarter.domain.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 对话消息表访问。多轮记忆与历史展示都读这里，每轮收尾时写入一问一答。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 取最近 N 条（按 id 倒序），调用方自行反转为时间正序 */
    List<ChatMessage> findBySessionIdOrderByIdDesc(Long sessionId, Pageable pageable);

    /** 删除会话时先清消息（库层面另有级联删除兜底，这里显式删以免依赖方言） */
    void deleteBySessionId(Long sessionId);
}
