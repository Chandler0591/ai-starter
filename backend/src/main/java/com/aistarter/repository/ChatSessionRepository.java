package com.aistarter.repository;

import com.aistarter.domain.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 会话表访问。列表默认按 id 倒序（最新在前），不分页。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /** 某个用户自己的会话（USER 只能看到这些） */
    List<ChatSession> findByUserIdOrderByIdDesc(Long userId);
}
