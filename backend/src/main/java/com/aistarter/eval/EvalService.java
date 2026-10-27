package com.aistarter.eval;

import com.aistarter.ai.ChatService;
import com.aistarter.ai.GuardrailViolationException;
import com.aistarter.ai.LlmClient;
import com.aistarter.dto.ChatRequest;
import com.aistarter.observability.TraceFilter;
import com.aistarter.security.AuthPrincipal;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 离线评测：Prompt / 模型 / 检索参数变更后跑一遍回归集，
 * 断言用「包含关键词」而非全文比对，避免真实模型措辞变化导致评测脆断。
 * 评测集为 classpath:eval/eval-cases.json，只读不写；结果仅存内存，重启即清。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Slf4j
@Service
public class EvalService {

    /** 评测集文件专用解析器，与 Web 层序列化配置互不影响（详见 {@link #loadCases()}） */
    private static final ObjectMapper CASE_MAPPER = new ObjectMapper();

    private final ChatService chatService;
    private final LlmClient llmClient;
    /** 最近一次评测报告；内存保存，并发重跑时后写完的生效 */
    private final AtomicReference<EvalReport> lastReport = new AtomicReference<>();

    /**
     * @param chatService 复用真实编排管道（护栏/RAG/工具），保证评测走的与线上同一条路
     * @param llmClient   仅用于在报告里标记本次是哪个 provider 跑出来的
     */
    public EvalService(ChatService chatService, LlmClient llmClient) {
        this.chatService = chatService;
        this.llmClient = llmClient;
    }

    /**
     * 顺序跑完整个回归集（不并发，避开 mock 延迟叠加带来的噪声）。
     * 单条用例失败不中断，继续跑完剩下用例，最后给出 passed/total。
     *
     * @param principal 执行身份；评测需要一个能访问工具与知识库的账号（当前为 admin）
     * @return 本次报告，同时写入 {@link #lastReport()}
     * @apiNote 副作用：每个用例都以 sessionId=null 进入管道，因此 chat_session 会新增若干
     * 条以用例问题为标题的会话；askOnce 不写消息，所以 chat_message 不会增长。
     */
    public EvalReport run(AuthPrincipal principal) {
        List<EvalCase> cases = loadCases();
        List<CaseResult> results = new ArrayList<>();
        for (EvalCase testCase : cases) {
            final String actual = resolveAnswer(testCase, principal);
            boolean passed = actual != null
                    && testCase.expectContains.stream().allMatch(actual::contains);
            results.add(new CaseResult(testCase.id, testCase.input, testCase.expectContains,
                    truncate(actual), passed));
            log.info("Eval case {} -> {}", testCase.id, passed ? "PASS" : "FAIL");
        }
        int passedCount = (int) results.stream().filter(CaseResult::passed).count();
        EvalReport report = new EvalReport(OffsetDateTime.now(), llmClient.provider(),
                results.size(), passedCount, results);
        lastReport.set(report);
        return report;
    }

    /** 单个用例的执行：护栏拦截与异常都转成可断言的文本，避免评测因为一条用例中断 */
    private String resolveAnswer(EvalCase testCase, AuthPrincipal principal) {
        try {
            return chatService.askOnce(new ChatRequest(testCase.input, null, "v1", false),
                    principal, "eval-" + TraceFilter.currentTraceId());
        } catch (GuardrailViolationException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** 取最近一次报告；从未跑过则返回 null，前端需做空态处理 */
    public EvalReport lastReport() {
        return lastReport.get();
    }

    /** 读 classpath 下的评测集；文件缺失或格式错误直接报错，不能当作“0 条用例”静默通过 */
    private List<EvalCase> loadCases() {
        try (InputStream in = new ClassPathResource("eval/eval-cases.json").getInputStream()) {
            // 评测集解析用独立的本地 mapper：Spring Boot 4 自动装配的是 Jackson 3（tools.jackson），
            // 这里只读静态配置，自己 new 一个最省事，也不会和 Web 层的序列化配置相互影响
            EvalFile file = CASE_MAPPER.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), EvalFile.class);
            return file.cases;
        } catch (IOException e) {
            throw new IllegalStateException("读取评测用例失败", e);
        }
    }

    /** 报告里的实际回答截断到 200 字，防止一条长回答把接口响应撑大 */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    /**
     * eval-cases.json 的顶层结构。
     *
     * @param name        评测集名称
     * @param description 人读说明，不参与断言
     * @param cases       用例列表
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvalFile(String name, String description, List<EvalCase> cases) {
    }

    /**
     * 单条用例。
     *
     * @param id            用例编号，日志与报告里用它定位
     * @param input         发给对话管道的问题原文
     * @param expectContains 回答必须包含的关键词，全部命中才算通过
     * @param comment       本条用例想防住的回归点（仅注释作用）
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvalCase(String id, String input, List<String> expectContains, String comment) {
    }

    /**
     * 单条用例的执行结果。
     *
     * @param actual 截断后的实际回答，失败时凭它直接判断差异
     */
    public record CaseResult(String id, String input, List<String> expectContains, String actual, boolean passed) {
    }

    /**
     * 一轮评测的汇总报告。
     *
     * @param ranAt    本次执行时间
     * @param provider 跑的模型提供方（mock / openai 等），用于区分不同环境的结果
     * @param total    用例总数
     * @param passed   通过数；小于 total 时 EvalRunner 会阻断启动
     */
    public record EvalReport(OffsetDateTime ranAt, String provider, int total, int passed, List<CaseResult> results) {
    }
}
