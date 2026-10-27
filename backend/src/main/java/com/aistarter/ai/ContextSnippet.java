package com.aistarter.ai;

/**
 * RAG 命中的上下文片段。
 *
 * @param text        切片正文
 * @param title       来源文档标题，用于回答里标注出处
 * @param documentId  知识文档 id，用于溯源与重新索引
 * @param score       向量相似度（mock 下为字面重合度）
 * @author Flynn
 * @date 2026-10-27
 */
public record ContextSnippet(String text, String title, Long documentId, double score) {
}
