package com.aistarter.repository;

import com.aistarter.domain.KnowledgeDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 知识文档表访问。向量片段由 Spring AI 的 documentRepository 另行维护，这里只管元数据。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public interface KnowledgeDocRepository extends JpaRepository<KnowledgeDoc, Long> {

    /** 全量列表，按 id 倒序（最新在前）；只有列表页需要的元数据字段会进视图，正文不外泄 */
    List<KnowledgeDoc> findAllByOrderByIdDesc();
}
