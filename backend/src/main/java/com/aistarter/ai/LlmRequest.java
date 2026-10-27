package com.aistarter.ai;

import java.util.List;
import java.util.Map;

/**
 * 模型调用的统一入参：把编排层准备好的上下文一次性打包，
 * Provider 实现只关心自己需要的字段（Mock 用统计字段生成确定回答，真实模型用 prompt + 工具）。
 *
 * @param traceId       链路号，与日志/响应头/落库同源
 * @param systemPrompt  已按版本解析完成的系统提示词
 * @param userPrompt    已注入历史/知识片段/工具结果的用户侧 Prompt
 * @param promptKey     系统提示词名，用于指标与回答溯源
 * @param promptVersion 实际命中的提示词版本
 * @param contextCount  RAG 命中片段数
 * @param toolNames     本轮暴露给模型的工具名（已按角色过滤）
 * @param toolBeans     工具对象实例，供原生 function calling
 * @param toolContext   传给工具的上下文（userId / admin），工具内据此做数据级隔离
 * @author Flynn
 * @date 2026-10-27
 */
public record LlmRequest(
        String traceId,
        String systemPrompt,
        String userPrompt,
        String promptKey,
        String promptVersion,
        int contextCount,
        List<String> toolNames,
        List<Object> toolBeans,
        Map<String, Object> toolContext) {
}
