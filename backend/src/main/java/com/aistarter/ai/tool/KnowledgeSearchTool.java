package com.aistarter.ai.tool;

import com.aistarter.ai.ContextSnippet;
import com.aistarter.ai.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 检索类工具：让模型可以主动补查知识库，而不是只能用预注入的上下文。
 * 仅对 ADMIN 开放（见 {@link ToolRegistry#toolsFor} 的角色白名单）。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool {

    private final RagService ragService;

    /**
     * 知识库补查，复用 RagService 的阈值与降级逻辑；无命中时固定回“未找到”，不给模型编造的余地。
     *
     * @param query 模型给出的检索关键词
     * @return 带序号、来源与相似度的片段列表文本
     */
    @Tool(name = "searchKnowledgeBase", description = "在企业管理知识库中检索资料（制度、流程、FAQ）。回答制度/流程类问题且已有上下文不足时使用。")
    public String searchKnowledgeBase(@ToolParam(description = "检索关键词，尽量具体") String query) {
        List<ContextSnippet> snippets = ragService.retrieve(query);
        if (snippets.isEmpty()) {
            return "知识库中未找到相关信息";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < snippets.size(); i++) {
            ContextSnippet s = snippets.get(i);
            sb.append('[').append(i + 1).append("] ").append(s.title())
                    .append(String.format(" (score=%.3f)%n", s.score()))
                    .append(s.text()).append('\n');
        }
        return sb.toString();
    }
}
