package com.aistarter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 对话请求。
 * sessionId 为空则新开一轮会话；promptVersion 支持 v1/v2/latest，用于 Prompt 灰度与 A-B。
 *
 * @param message       用户问题，非空且 ≤ 8000 字符（另有护栏上限 4000）
 * @param sessionId     会话 id；为空新建，非空则校验属主
 * @param promptVersion 系统提示词版本，为空默认 v1
 * @param useTools      是否启用工具；为 null 视为开启
 * @author Flynn
 * @date 2026-10-27
 */
public record ChatRequest(
        @NotBlank(message = "问题不能为空")
        @Size(max = 8000, message = "问题过长")
        String message,
        Long sessionId,
        String promptVersion,
        Boolean useTools) {

    /** 前端不传也算开启（null -> true），方便对照实验时显式传 false 关闭 */
    public boolean toolsEnabled() {
        return useTools == null || useTools;
    }
}
