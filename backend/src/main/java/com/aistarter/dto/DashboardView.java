package com.aistarter.dto;

import java.util.List;
import java.util.Map;

/**
 * 运行看板响应体：一次拉齐「当前 provider + Prompt 目录 + 知识规模 + AI 指标」。
 *
 * @param provider      当前模型提供方（mock / openai）
 * @param prompts       提示词名 -> 可用版本列表，供前端下拉选择
 * @param knowledgeDocs 知识文档总数
 * @param metrics       ai.* 指标行
 * @author Flynn
 * @date 2026-10-27
 */
public record DashboardView(
        String provider,
        Map<String, List<String>> prompts,
        long knowledgeDocs,
        List<MetricRow> metrics) {

    /**
     * 一行指标。
     *
     * @param name   指标名，如 ai.llm.latency
     * @param tags   维度，如 provider/type/success
     * @param values Micrometer 统计值（count/totalAmount/max/VALUE 等）
     */
    public record MetricRow(String name, Map<String, String> tags, Map<String, Double> values) {
    }
}
