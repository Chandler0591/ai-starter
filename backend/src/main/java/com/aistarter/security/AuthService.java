package com.aistarter.security;

import com.aistarter.config.JwtProperties;
import com.aistarter.domain.AppUser;
import com.aistarter.dto.AuthDtos;
import com.aistarter.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录与身份查询。
 * 实现 {@link UserDetailsService} 是为了接上 Spring Security 标准用户装载链路，
 * 同时避免自动配置生成「内存默认用户 + 随机密码」的误导行为。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Service
@RequiredArgsConstructor
public class AuthService implements UserDetailsService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    /**
     * 给 Spring Security 标准链路用（也顺带避免启动时生成「内存默认用户 + 随机密码」）。
     * 当前登录走下面的账号密码校验，若换成 DaoAuthenticationProvider 可直接复用本方法。
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .disabled(!user.isEnabled())
                .authorities(user.getRole().authority())
                .build();
    }

    /**
     * 账号密码登录：先查启用账号，再校 BCrypt 口令；失败统一回“用户名或密码错误”，
     * 不区分“用户不存在”与“密码错”，避免账号枚举。
     *
     * @param request 登录参数
     * @return 令牌 + 身份 + 有效期
     * @throws BadCredentialsException 账号不存在/被禁用/口令不符（HTTP 401）
     */
    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request) {
        AppUser user = userRepository.findByUsername(request.username())
                .filter(AppUser::isEnabled)
                .orElseThrow(() -> new BadCredentialsException("用户名或密码错误"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        return new AuthDtos.LoginResponse(
                jwtService.issue(user),
                "Bearer",
                jwtProperties.getExpireMinutes() * 60,
                user.getUsername(),
                user.getRole().name());
    }

    /** 当前登录人信息，给前端顶栏与权限展示用（数据取自库，不信令牌里的显示名） */
    public AuthDtos.MeResponse me(AuthPrincipal principal) {
        AppUser user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new BadCredentialsException("用户不存在"));
        return new AuthDtos.MeResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().name());
    }

    /**
     * 认证失败统一异常，HTTP 401。
     *
     * @param message 对外可读文案（不回“用户不存在”这类可枚举信息）
     */
    public static class BadCredentialsException extends RuntimeException {
        /** @param message 展示给用户的可读文案 */
        public BadCredentialsException(String message) {
            super(message);
        }
    }
}
