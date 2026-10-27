package com.aistarter.domain;

/**
 * 知识文档的索引状态。只有 INDEXED 的文档可被 RAG 召回；
 * FAILED 不会阻断录入（业务记录保留），可用 reindex 重试。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public enum IndexStatus {

    /** 已落库、尚未向量化 */
    PENDING,
    /** 切片与向量写入完成 */
    INDEXED,
    /** 向量化失败，保留原文待重试 */
    FAILED
}
