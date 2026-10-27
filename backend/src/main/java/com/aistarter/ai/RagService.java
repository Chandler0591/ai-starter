package com.aistarter.ai;

import com.aistarter.config.AiProperties;
import com.aistarter.domain.KnowledgeDoc;
import com.aistarter.observability.AiMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * RAG：切片入库 + 阈值检索 + 失败降级。
 * 换向量库（Milvus/Redis/Qdrant）只需替换 VectorStore 的 starter，本类不改。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    /** 向量元数据键：documentId 用于按文档精确清理旧向量，title/source 用于回答里标注出处 */
    public static final String META_DOCUMENT_ID = "documentId";
    public static final String META_TITLE = "title";
    public static final String META_SOURCE = "source";

    private final VectorStore vectorStore;
    private final AiProperties properties;
    private final AiMetrics metrics;
    private final LlmClient llmClient;

    /**
     * 重新入库：先按 documentId 清理旧向量，再切片写入（因此重复调不会造成同文档片段叠加）。
     *
     * @param doc 知识文档实体（取 id/title/source/content）
     * @return 实际写入的片段数，回写 knowledge_doc.chunk_count
     */
    public int index(KnowledgeDoc doc) {
        deleteByDocumentId(doc.getId());
        List<Document> chunks = splitter().split(List.of(new Document(doc.getContent(), Map.of(
                META_DOCUMENT_ID, doc.getId(),
                META_TITLE, doc.getTitle(),
                META_SOURCE, doc.getSource()))));
        if (!chunks.isEmpty()) {
            vectorStore.add(chunks);
        }
        log.info("Indexed doc={} chunks={}", doc.getId(), chunks.size());
        return chunks.size();
    }

    /** 按 documentId 删除该文档的全部向量（删除文档、重建索引前都走这里） */
    public void deleteByDocumentId(Long documentId) {
        try {
            vectorStore.delete(new FilterExpressionBuilder().eq(META_DOCUMENT_ID, documentId).build());
        } catch (Exception e) {
            // 首次入库时向量表/元数据列可能尚不存在，清理失败不阻断主流程
            log.warn("Delete vectors of doc={} failed: {}", documentId, e.getMessage());
        }
    }

    /**
     * 相似度检索：topK + 阈值由配置决定，命中结果携带来源与分数供溯源。
     *
     * @param query 用户问题
     * @return 命中片段（可能为空）；检索异常时固定返回空列表，由上层走「无上下文」降级
     */
    public List<ContextSnippet> retrieve(String query) {
        long start = System.currentTimeMillis();
        try {
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(properties.getRag().getTopK())
                    .similarityThreshold(threshold())
                    .build());
            List<ContextSnippet> snippets = documents.stream()
                    .map(d -> new ContextSnippet(
                            d.getText(),
                            String.valueOf(d.getMetadata().getOrDefault(META_TITLE, "未命名文档")),
                            asLong(d.getMetadata().get(META_DOCUMENT_ID)),
                            d.getScore() == null ? 0d : d.getScore()))
                    .toList();
            metrics.recordRag(snippets.size(), System.currentTimeMillis() - start);
            return snippets;
        } catch (Exception e) {
            log.warn("RAG retrieval failed, fallback to empty context: {}", e.getMessage());
            metrics.recordDegradation("rag");
            metrics.recordRag(0, System.currentTimeMillis() - start);
            return List.of();
        }
    }

    /** mock provider 的相似度是字面重合度，量级低，需单独阈值（宁可拒答，不可编造） */
    private double threshold() {
        return "mock".equals(llmClient.provider())
                ? properties.getRag().getMockMinScore()
                : properties.getRag().getMinScore();
    }

    /** 每次入库新建切片器：参数直接取自 ai.rag.*，不共享实例也就不会残留旧配置 */
    private TokenTextSplitter splitter() {
        // jtokkit 按 token 切分，中文场景 chunkSize 取得比英文小一些
        return TokenTextSplitter.builder()
                .withChunkSize(properties.getRag().getChunkSize())
                .withMinChunkSizeChars(150)
                .withKeepSeparator(true)
                .build();
    }

    /** 元数据里的文档 id 安全转 long，非数字（或缺失）返回 null */
    private Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
