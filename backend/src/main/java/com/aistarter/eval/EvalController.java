package com.aistarter.eval;

import com.aistarter.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手工触发评测（ADMIN）：改完 Prompt 或换模型后，前端点一下即可回归。
 * 与 {@link EvalRunner} 共用同一个 {@link EvalService}，区别只在触发时机。
 * 接口规则在 SecurityConfig 里收为 hasRole("ADMIN")。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@RestController
@RequestMapping("/api/eval")
@RequiredArgsConstructor
public class EvalController {

    private final EvalService evalService;

    /**
     * 同步跑一遍回归集。用例走完整管道，每条至少叠上 {@code ai.mock.latency-ms}（默认 200ms），
     * 条数多时会占住请求线程，目测不适合高并发调用；若需异步可改成任务队列。
     */
    @PostMapping("/run")
    public EvalService.EvalReport run() {
        return evalService.run(SecurityUtils.requirePrincipal());
    }

    /** 取最近一次报告；服务启动后从未跑过评测则返回 null */
    @GetMapping("/report")
    public EvalService.EvalReport report() {
        return evalService.lastReport();
    }
}
