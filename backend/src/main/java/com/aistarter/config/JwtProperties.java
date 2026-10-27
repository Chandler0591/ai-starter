package com.aistarter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置（前缀 jwt.），均可由环境变量覆盖。
 * 生产环境必须换掉默认密钥，否则任何人可自签令牌。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Data
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** HS256 密钥，生产环境必须通过环境变量注入（>= 32 字节） */
    private String secret = "change-me-in-production-please-use-a-long-random-secret-key-256bit";

    /** 令牌有效期（分钟），登录响应里的 expiresInSeconds 由此换算 */
    private long expireMinutes = 120;
}
