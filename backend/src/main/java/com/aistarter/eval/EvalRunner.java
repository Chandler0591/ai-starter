package com.aistarter.eval;

import com.aistarter.domain.Role;
import com.aistarter.repository.AppUserRepository;
import com.aistarter.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动即评测：`mvn spring-boot:run -Dspring-boot.run.arguments=--eval=true`
 * CI 里用它做 Prompt/模型变更的准入门禁（失败非零退出，可直接卡流水线）。
 * 不带 --eval 参数时本类直接返回，正常启动不跑评测。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class EvalRunner implements ApplicationRunner {

    /** 启动参数名：只有带 --eval 时才跑评测 */
    private static final String EVAL_FLAG = "eval";

    private final EvalService evalService;
    private final AppUserRepository userRepository;

    /**
     * {@code @Order(20)} 排在 DataSeeder（{@code @Order(10)}）之后，保证 admin 账号已就位。
     * 评测不通过时抛异常中断启动，让容器/CI 拿到非零退出码。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(EVAL_FLAG)) {
            return;
        }
        // 评测需要固定身份才能走到工具与知识库分支，这里直接取种子数据里的 admin，而不走登录
        AuthPrincipal system = userRepository.findByUsername("admin")
                .map(user -> new AuthPrincipal(user.getId(), user.getUsername(), Role.ROLE_ADMIN))
                .orElseThrow(() -> new IllegalStateException("评测需要一个管理员账号"));

        EvalService.EvalReport report = evalService.run(system);
        log.info("Eval done: provider={} passed={}/{}", report.provider(), report.passed(), report.total());
        report.results().stream()
                .filter(r -> !r.passed())
                .forEach(r -> log.warn("Eval FAIL case={} input={} expect={} actual={}",
                        r.id(), r.input(), r.expectContains(), r.actual()));
        if (report.passed() < report.total()) {
            throw new IllegalStateException("离线评测未通过，阻断启动（回归门禁）");
        }
    }
}
