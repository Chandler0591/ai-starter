package com.aistarter.controller;

import com.aistarter.dto.AuthDtos;
import com.aistarter.security.AuthService;
import com.aistarter.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证入口：登录换令牌、查当前身份。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 账号密码登录，返回 Bearer 令牌。这是 /api 下唯一允许匿名访问的写接口；
     * 请求体必须带 {@code Content-Type: application/json}，否则服务端返回 415。
     */
    @PostMapping("/login")
    public AuthDtos.LoginResponse login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return authService.login(request);
    }

    /** 当前登录人信息，主要用于前端刷新后恢复身份 */
    @GetMapping("/me")
    public AuthDtos.MeResponse me() {
        return authService.me(SecurityUtils.requirePrincipal());
    }
}
