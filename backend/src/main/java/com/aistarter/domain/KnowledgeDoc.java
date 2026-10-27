package com.aistarter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 知识库文档元数据；切片与向量存于 pgvector 表，删除时按 documentId 清理。
 * 原文长期保留，因此换 embedding 模型后只重建向量即可，不必重新录入。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Entity
@Table(name = "knowledge_doc")
@Getter
@Setter
public class KnowledgeDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    /** 来源标识：MANUAL / FILE / URL */
    @Column(nullable = false, length = 64)
    private String source;

    /** 原文（TEXT）：切片与向量存到 pgvector 表，这里只留可重建索引的原文 */
    @Column(nullable = false)
    private String content;

    /** 索引片段数，由 RagService.index 回写；为 0 通常意味着向量化失败 */
    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IndexStatus status = IndexStatus.PENDING;

    /** 录入人 id，仅用于审计，列表接口不对外返回 */
    @Column(name = "created_by")
    private Long createdBy;

    /** 入库时间（建实体时取 now） */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    /** 最后修改时间：录入与 reindex 时由服务层显式回写（无 @PreUpdate） */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
