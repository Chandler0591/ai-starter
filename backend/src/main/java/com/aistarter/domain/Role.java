package com.aistarter.domain;

/**
 * 两级角色。枚举名直接用作 Spring Security 的 authority，因此无需额外前缀转换。
 * 新增角色时要同步考虑 SecurityConfig 的接口规则与 ToolRegistry 的工具白名单。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public enum Role {
    /** 普通员工：对话 + 只读知识库，工具只能查本人数据 */
    ROLE_USER,
    /** 管理员：额外可写知识库、跑评测、看运行看板 */
    ROLE_ADMIN;

    /** 以枚举名作为权限串，与 JWT 里的 role 声明一致 */
    public String authority() {
        return name();
    }
}
