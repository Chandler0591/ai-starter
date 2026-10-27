package com.aistarter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * 知识库相关 DTO 集合。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public final class KnowledgeDtos {

    /** 仅作为内嵌 record 的命名空间容器，禁止实例化 */
    private KnowledgeDtos() {
    }

    /**
     * 手工录入或文件解析后的纯文本入库。
     *
     * @param title   标题，非空且 ≤ 200 字
     * @param content 正文，非空（切片与向量化的原始语料）
     * @param source  来源标记；为空默认 MANUAL，文件上传写 FILE
     */
    public record UploadRequest(
            @NotBlank(message = "标题不能为空") @Size(max = 200) String title,
            @NotBlank(message = "内容不能为空") String content,
            String source) {
    }

    /**
     * 文档列表项（不含正文）。
     *
     * @param status INDEXED / PENDING / FAILED，FAILED 可用 reindex 重试
     */
    public record DocView(Long id, String title, String source, int chunkCount, String status,
                          OffsetDateTime createdAt) {
    }
}
