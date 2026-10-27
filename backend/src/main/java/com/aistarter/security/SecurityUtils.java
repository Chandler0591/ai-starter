package com.aistarter.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * 从 SecurityContext 里取当前登录主体的工具方法。
 * controller 靠它拿到 principal，再把身份传给会话/工具层做属主校验。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public final class SecurityUtils {

    /** 工具类，不实例化 */
    private SecurityUtils() {
    }

    /** 当前认证主体；未登录或不是本项目 JWT 主体时返回 empty */
    public static Optional<AuthPrincipal> currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    /** 必须登录才能继续的场景（对话、会话、知识写接口），拿不到就报错 */
    public static AuthPrincipal requirePrincipal() {
        return currentPrincipal().orElseThrow(() -> new IllegalStateException("未认证用户"));
    }
}
