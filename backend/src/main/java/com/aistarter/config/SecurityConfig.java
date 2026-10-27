package com.aistarter.config;

import com.aistarter.observability.TraceFilter;
import com.aistarter.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 权限模型：JWT 无状态认证 + RBAC 两级角色。
 * ADMIN：知识库写入、评测、指标看板；USER：对话与只读查看。
 * 这里只是「接口级」关卡，另有数据级（会话属主）与工具级（角色白名单）两层。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /** 口令存 BCrypt，不用可逆加密；登录时由 AuthService 校验 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 安全过滤链。注意规则顺序敏感：先放行 ASYNC/ERROR dispatch，再按路径定权限。
     * 未认证/无权限统一回 JSON（不跳登录页），便于前端按状态码做登出与提示。
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        // SSE/Flux 完成时会做一次 ASYNC（出错时还有 ERROR）dispatch 重新进入过滤器链；
                        // OncePerRequestFilter 默认不在 ASYNC dispatch 上跑，SecurityContext 因此是空的，
                        // 不放行就会抛 AuthorizationDeniedException 并把已提交的响应硬切断（客户端表现为流不关闭）。
                        // 首次 REQUEST dispatch 已完成认证授权，这里放行不会降低安全性。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // 知识库：读需要登录，写仅 ADMIN
                        .requestMatchers(HttpMethod.GET, "/api/knowledge/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/knowledge/**").hasRole("ADMIN")
                        .requestMatchers("/api/eval/**").hasRole("ADMIN")
                        .requestMatchers("/api/dashboard/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("USER", "ADMIN")
                        .anyRequest().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> write(res, 401, "未认证或登录已过期"))
                        .accessDeniedHandler((req, res, e) -> write(res, 403, "无权限访问该资源")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 统一错误体：与 ApiExceptionHandler 同构（{status,message,traceId}），方便前端一处处理 */
    private void write(HttpServletResponse res, int status, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message
                + "\",\"traceId\":\"" + TraceFilter.currentTraceId() + "\"}");
    }
}
