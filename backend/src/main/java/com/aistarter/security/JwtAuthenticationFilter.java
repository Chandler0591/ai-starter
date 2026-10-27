package com.aistarter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 无状态 Bearer Token 认证：解析 JWT 并写入 SecurityContext。
 * 解析失败不直接拒绝，交给授权规则决定是否放行（匿名接口仍可访问）。
 * <p>注意：{@code OncePerRequestFilter} 默认不在 ASYNC dispatch 上执行，
 * 因此 SSE 收尾时上下文为空——配套规则在 SecurityConfig 里放行了 ASYNC/ERROR dispatch。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Authorization 头的方案前缀 */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    /**
     * 仅在「已有 Bearer 头且当前上下文未认证」时尝试解析令牌；
     * 解析成功则把 {@link AuthPrincipal} 与角色权限写入上下文，失败保持匿名继续跑链。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            jwtService.parse(header.substring(BEARER_PREFIX.length()))
                    .ifPresent(principal -> {
                        var auth = new UsernamePasswordAuthenticationToken(
                                principal, null,
                                List.of(new SimpleGrantedAuthority(principal.role().authority())));
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
        }
        chain.doFilter(request, response);
    }
}
