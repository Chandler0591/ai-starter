package com.aistarter.ai;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mock 向量化：字符二元组 feature hashing + L2 归一化，完全本地计算、结果确定。
 * 语义相似度约等于字面重合度，因此 mock 模式下 ai.rag.min-score 需调低（见 README）。
 * 切换到 provider=openai 后本实现不注册，由 OpenAI EmbeddingModel 接管，向量维度须与表结构一致。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public class MockEmbeddingModel implements EmbeddingModel {

    /** 向量维度，必须与 ai_document_embedding 表列维度一致（AI_EMBEDDING_DIMENSION） */
    private final int dimension;

    /**
     * @param dimension 向量维度，来自 {@code ai.embedding.dimension}
     */
    public MockEmbeddingModel(int dimension) {
        this.dimension = dimension;
    }

    /** 批量向量化：按 instructions 顺序逐条生成，下标即 index */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> instructions = request.getInstructions();
        for (int i = 0; i < instructions.size(); i++) {
            embeddings.add(new Embedding(vectorize(instructions.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    /** 单文档向量化（Spring AI 切片入库时会逐片调用） */
    @Override
    public float[] embed(Document document) {
        return vectorize(document.getText());
    }

    /** 查询向量化：与入库同一算法，保证同一文本得到同一向量 */
    @Override
    public float[] embed(String text) {
        return vectorize(text);
    }

    /** 声明维度，PgVectorStore 建表与校验依赖此值 */
    @Override
    public int dimensions() {
        return dimension;
    }

    /**
     * 核心向量化算法：小写去空白后取相邻字符二元组，按 hashCode 映射到维度位置累加，
     * 最后 L2 归一化（保证余弦相似度落在 [0,1]）。
     * 字面重合越多向量越近，所以只能算「浅语义」，阈值需调低。
     *
     * @param text      待向量化文本，空文本返回全零向量
     * @param dimension 目标维度
     * @return 已归一化的稠密向量
     */
    static float[] vectorize(String text, int dimension) {
        float[] vector = new float[dimension];
        if (text == null || text.isBlank()) {
            return vector;
        }
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        for (int i = 0; i < normalized.length() - 1; i++) {
            String bigram = normalized.substring(i, i + 2);
            int index = Math.abs(bigram.hashCode() % dimension);
            vector[index] += 1f;
        }
        // 末字符单独补一维，避免短文本全零
        int lastIndex = Math.abs(String.valueOf(normalized.charAt(normalized.length() - 1)).hashCode() % dimension);
        vector[lastIndex] += 0.5f;

        double sum = 0;
        for (float f : vector) {
            sum += f * f;
        }
        double norm = Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }

    /** 实例方法入口，用本实例配置的维度做向量化 */
    public float[] vectorize(String text) {
        return vectorize(text, dimension);
    }
}
