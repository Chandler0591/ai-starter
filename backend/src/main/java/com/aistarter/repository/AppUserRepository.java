package com.aistarter.repository;

import com.aistarter.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 用户表访问。查询全部用方法名推导，不手写 JPQL。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** 登录与令牌解析后的用户装载 */
    Optional<AppUser> findByUsername(String username);

    /** 预留：按登录名判存在（当前种子与登录都走 findByUsername，加注册接口时可直接复用） */
    boolean existsByUsername(String username);
}
