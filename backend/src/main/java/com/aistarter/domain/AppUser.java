package com.aistarter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 业务用户。示例采用单角色模型（够用且直观）；
 * 若需多角色，把 role 改为 @ManyToMany 的 roles 集合即可，其余代码不变。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录名，库内唯一 */
    @Column(nullable = false, unique = true)
    private String username;

    /** BCrypt 哈希，绝不存明文；字段长度 100 兼容 bcrypt 的 60 位输出 */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    /** 枚举名入库（不依赖序号），角色变更不会串位 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Role role;

    /** 停用账号：登录直接当「用户名或密码错误」处理，不删历史数据 */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 创建时间：建实体时取 now（未接 JPA Auditing） */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
