package com.aistarter.ai;

import com.aistarter.config.AiProperties;
import com.aistarter.observability.AiMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 输入侧护栏：长度/空值校验 + Prompt 注入特征过滤。
 * 输出侧护栏（敏感内容、幻觉校验）可作为 checkOutput() 扩展点，位置已预留在 ChatService 收尾处。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Component
@RequiredArgsConstructor
public class GuardrailService {

    /** 注入特征库：中英常见越狱/提示词泄露句式，命中即拦，不进模型 */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("忽略(以上|上述|所有|之前).{0,6}(指令|提示词|设定)"),
            Pattern.compile("ignore\\s+(all\\s+|any\\s+)?(previous|above|prior)\\s+instructions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(system\\s*prompt|系统提示词).{0,8}(输出|打印|重复|reveal|show)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(reveal|show)\\s+(the\\s+)?system\\s+prompt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(你现在是|assume\\s+you\\s+are|developer\\s+mode|越狱|jailbreak)", Pattern.CASE_INSENSITIVE)
    );

    private final AiProperties properties;
    private final AiMetrics metrics;

    /**
     * 输入校验：空值 -> 超长 -> 注入特征，依次拦截。
     * 放在编排最前面，被拦下的输入不产生任何模型调用与 token 成本。
     *
     * @throws GuardrailViolationException 命中任一规则（同时计入 ai.guardrail.blocked{reason}）
     */
    public void checkInput(String input) {
        if (input == null || input.isBlank()) {
            block("EMPTY", "输入不能为空");
        }
        if (input.length() > properties.getGuardrail().getMaxInputChars()) {
            block("TOO_LONG", "输入被安全护栏拦截：超长（最大 "
                    + properties.getGuardrail().getMaxInputChars() + " 字符）");
        }
        if (properties.getGuardrail().isInjectionCheck()) {
            for (Pattern pattern : INJECTION_PATTERNS) {
                if (pattern.matcher(input).find()) {
                    block("INJECTION", "输入被安全护栏拦截：检测到指令注入特征");
                }
            }
        }
    }

    /** 先记指标再抛异常，保证拦截量不因上层如何处理而丢失 */
    private void block(String reason, String message) {
        metrics.recordGuardrailBlock(reason);
        throw new GuardrailViolationException(reason, message);
    }
}
