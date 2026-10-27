package com.aistarter.security;

import com.aistarter.domain.Role;

import java.io.Serializable;

/**
 * 认证主体：贯穿请求上下文，工具调用时通过它做数据级权限过滤。
 * 只放可公开的信息（id/用户名/角色），不携带口令等敏感字段。
 *
 * @param userId   数据库主键，作为工具上下文 userId
 * @param username 登录名
 * @param role     角色，决定接口与工具可见范围
 * @author Flynn
 * @date 2026-10-27
 */
public record AuthPrincipal(Long userId, String username, Role role) implements Serializable {

    /** 是否管理员：用于会话属主放宽与工具白名单判定 */
    public boolean isAdmin() {
        return role == Role.ROLE_ADMIN;
    }
}
