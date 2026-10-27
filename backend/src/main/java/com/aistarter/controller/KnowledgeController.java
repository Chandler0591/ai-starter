package com.aistarter.controller;

import com.aistarter.ai.KnowledgeService;
import com.aistarter.dto.KnowledgeDtos;
import com.aistarter.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 知识库管理入口。读接口 USER/ADMIN 均可，写接口仅 ADMIN（规则在 SecurityConfig）。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    /** 文档列表（含片段数与索引状态），不返回正文 */
    @GetMapping
    public List<KnowledgeDtos.DocView> list() {
        return knowledgeService.list();
    }

    /** 手工录入文本（ADMIN） */
    @PostMapping
    public KnowledgeDtos.DocView upload(@Valid @RequestBody KnowledgeDtos.UploadRequest request) {
        return knowledgeService.upload(request, SecurityUtils.requirePrincipal());
    }

    /**
     * 文件上传（ADMIN）：仅演示 .txt/.md，PDF/DOCX 解析接入点留在此处。
     * 当前直接按 UTF-8 读字节，未做类型/大小白名单，生产接入请补校验与病毒扫描。
     *
     * @param file multipart 字段名固定为 file，标题取原文件名
     */
    @PostMapping("/file")
    public KnowledgeDtos.DocView uploadFile(@RequestParam("file") MultipartFile file) throws IOException {
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        String title = file.getOriginalFilename() == null ? "上传文档" : file.getOriginalFilename();
        return knowledgeService.upload(new KnowledgeDtos.UploadRequest(title, text, "FILE"),
                SecurityUtils.requirePrincipal());
    }

    /** 重建索引：换切片参数/换 embedding 模型、或索引失败重试时调 */
    @PostMapping("/{id}/reindex")
    public KnowledgeDtos.DocView reindex(@PathVariable Long id) {
        return knowledgeService.reindex(id);
    }

    /** 删除文档：业务记录与对应向量一起清 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        knowledgeService.delete(id);
    }
}
