package com.aistarter.ai;

import com.aistarter.domain.IndexStatus;
import com.aistarter.domain.KnowledgeDoc;
import com.aistarter.dto.KnowledgeDtos;
import com.aistarter.repository.KnowledgeDocRepository;
import com.aistarter.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 知识库管理：入库即切片 + 向量化；删除同时清理向量。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeDocRepository repository;
    private final RagService ragService;

    /**
     * 新文档入库：先落业务记录，再切片向量化。
     * 向量环节失败不回滚业务记录，而是标记 FAILED 供后续 reindex 重试。
     *
     * @param request   标题/正文/来源（来源为空时默认 MANUAL）
     * @param principal 当前用户，写入 created_by 供审计
     * @return 文档视图（含 chunkCount 与索引状态）
     */
    @Transactional
    public KnowledgeDtos.DocView upload(KnowledgeDtos.UploadRequest request, AuthPrincipal principal) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle(request.title());
        doc.setContent(request.content());
        doc.setSource(request.source() == null ? "MANUAL" : request.source());
        doc.setCreatedBy(principal.userId());
        doc = repository.save(doc);

        try {
            int chunks = ragService.index(doc);
            doc.setChunkCount(chunks);
            doc.setStatus(IndexStatus.INDEXED);
        } catch (Exception e) {
            // 向量入库失败不回滚业务记录，标记 FAILED 供重试
            log.error("Index doc={} failed", doc.getId(), e);
            doc.setStatus(IndexStatus.FAILED);
        }
        doc.setUpdatedAt(OffsetDateTime.now());
        return toView(repository.save(doc));
    }

    /** 重新索引（改了 Prompt 切片参数或 Embedding 模型后回归用） */
    @Transactional
    public KnowledgeDtos.DocView reindex(Long id) {
        KnowledgeDoc doc = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + id));
        int chunks = ragService.index(doc);
        doc.setChunkCount(chunks);
        doc.setStatus(IndexStatus.INDEXED);
        doc.setUpdatedAt(OffsetDateTime.now());
        return toView(repository.save(doc));
    }

    /**
     * 删除文档：业务记录与向量一起清理，避免残留向量继续被召回。
     *
     * @throws IllegalArgumentException 文档不存在
     */
    @Transactional
    public void delete(Long id) {
        KnowledgeDoc doc = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + id));
        ragService.deleteByDocumentId(doc.getId());
        repository.delete(doc);
    }

    /** 文档列表，按 id 倒序 */
    public List<KnowledgeDtos.DocView> list() {
        return repository.findAllByOrderByIdDesc().stream().map(this::toView).toList();
    }

    /** 实体 -> 对外视图（不暴露正文与 created_by，列表页只展示索引概况） */
    private KnowledgeDtos.DocView toView(KnowledgeDoc doc) {
        return new KnowledgeDtos.DocView(doc.getId(), doc.getTitle(), doc.getSource(),
                doc.getChunkCount(), doc.getStatus().name(), doc.getCreatedAt());
    }
}
