package com.aistarter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 认证相关 DTO 集合（请求/响应体集中放一起，便于与前端 types.ts 对齐）。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public final class AuthDtos {

    /** 仅作为内嵌 record 的命名空间容器，禁止实例化 */
    private AuthDtos() {
    }

    /**
     * 登录请求。
     *
     * @param username 登录名，非空
     * @param password 明文口令（仅在 HTTPS 传输中瞬存在，不落库、不落日志）
     */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /**
     * 登录成功响应。
     *
     * @param token            JWT 访问令牌
     * @param tokenType        固定 Bearer
     * @param expiresInSeconds 有效期，由 jwt.expire-minutes 换算
     * @param username         登录名
     * @param role             角色，前端据此过滤菜单与路由
     */
    public record LoginResponse(String token, String tokenType, long expiresInSeconds, String username, String role) {
    }

    /** 当前登录人信息（取自库，不信赖令牌里的历史声明） */
    public record MeResponse(Long id, String username, String displayName, String role) {
    }
}
