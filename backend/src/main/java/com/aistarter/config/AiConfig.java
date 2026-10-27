package com.aistarter.config;

import com.aistarter.ai.LlmClient;
import com.aistarter.ai.MockEmbeddingModel;
import com.aistarter.ai.MockLlmClient;
import com.aistarter.ai.SpringAiLlmClient;
import com.aistarter.observability.AiMetrics;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Provider 装配：ai.provider=mock（默认，无需 API Key）| openai（OpenAI 兼容网关）。
 * 业务层只注入 LlmClient 接口，切换 Provider 不改一行业务代码。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Configuration
public class AiConfig {

    /** 默认实现：本地确定回答，同时给 mock 模式补齐 ai.llm.* 指标 */
    @Bean
    @ConditionalOnProperty(name = "ai.provider", havingValue = "mock", matchIfMissing = true)
    public LlmClient mockLlmClient(AiMetrics metrics, AiProperties properties) {
        return new MockLlmClient(metrics, properties);
    }

    /**
     * mock 模式下的本地向量化实现。
     * OpenAI 自动配置里的 @ConditionalOnMissingBean 是按具体类型（OpenAiEmbeddingModel）推导的，
     * 不会因为我们这个 EmbeddingModel 而退让，所以这里用 @Primary 保证 PgVectorStore 注入到 mock 实现，
     * 无需 API Key 也不会发起外部请求。
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "ai.provider", havingValue = "mock", matchIfMissing = true)
    public EmbeddingModel mockEmbeddingModel(AiProperties properties) {
        return new MockEmbeddingModel(properties.getEmbedding().getDimension());
    }

    /** 真实模型分支：仅 ai.provider=openai 时装配，依赖 spring-ai-starter-model-openai 的自动配置 */
    @Bean
    @ConditionalOnProperty(name = "ai.provider", havingValue = "openai")
    public LlmClient springAiLlmClient(ChatClient.Builder builder, AiMetrics metrics, AiProperties properties) {
        // ChatClient.Builder 由 spring-ai-starter-model-openai 自动装配，
        // 默认已挂载 ToolCallingAdvisor，支持 @Tool 原生 function calling
        return new SpringAiLlmClient(builder.build(), metrics, properties.getTimeoutMs());
    }
}
