package com.aistarter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 开发期前端走 Vite proxy 不产生跨域；此处保留 CORS 以支持前后端独立部署/联调。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Configuration
public class WebConfig {

    /**
     * CORS 规则：只放本地来源 + 必要请求头（含 Authorization），并把 X-Trace-Id 暴露给前端读取。
     * 生产同域反代时不会命中；若前后端分域，请把 allowedOriginPatterns 收敛到实际域名。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Trace-Id"));
        config.setExposedHeaders(List.of("X-Trace-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
