package com.aistarter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 工程化相关配置集中入口（前缀 ai.）。
 * 所有阈值都可外部化，便于灰度调参与压测对比。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Data
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** mock | openai */
    private String provider = "mock";

    /** 单次模型调用超时（毫秒） */
    private long timeoutMs = 30_000L;

    /** 多轮对话携带的历史消息条数 */
    private int maxHistory = 8;

    /** 护栏子配置（ai.guardrail.*） */
    private Guardrail guardrail = new Guardrail();
    /** RAG 子配置（ai.rag.*） */
    private Rag rag = new Rag();
    /** 向量化子配置（ai.embedding.*） */
    private Embedding embedding = new Embedding();
    /** mock provider 子配置（ai.mock.*） */
    private Mock mock = new Mock();

    /** 输入侧护栏参数 */
    @Data
    public static class Guardrail {
        /** 输入长度上限 */
        private int maxInputChars = 4000;
        /** 是否启用 Prompt 注入模式检测 */
        private boolean injectionCheck = true;
    }

    /** 检索与切片参数 */
    @Data
    public static class Rag {
        /** 每次检索返回的最大片段数；召回不足先加 topK 再调阈值 */
        private int topK = 4;
        /** 相似度阈值（真实 Embedding 模型）低于该分数视为未命中，走拒答降级 */
        private double minScore = 0.65;
        /**
         * Mock Embedding 的分数本质是字面二元组重合度，量级远低于语义向量，
         * 因此 mock provider 单独用一个更低阈值，保证两条链路都能演示「命中/未命中」。
         */
        private double mockMinScore = 0.12;
        /** 切片目标大小（token）；中文场景取比英文更小的值 */
        private int chunkSize = 400;
    }

    /** 向量化维度配置 */
    @Data
    public static class Embedding {
        /** 必须与向量库表结构、模型输出维度一致 */
        private int dimension = 1536;
    }

    /** mock provider 行为参数 */
    @Data
    public static class Mock {
        /** mock provider 的标称推理耗时（毫秒），用于让延迟指标在离线演示时也有真实量级 */
        private long latencyMs = 200L;
    }
}
