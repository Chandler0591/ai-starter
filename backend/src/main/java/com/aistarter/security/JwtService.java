package com.aistarter.security;

import com.aistarter.config.JwtProperties;
import com.aistarter.domain.AppUser;
import com.aistarter.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 签发与校验（JJWT，HS256）。
 * 令牌里只放 uid / username / role，不落任何敏感信息；无状态因此后端可水平扩容。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Slf4j
@Service
public class JwtService {

    private final JwtProperties properties;
    /** HMAC 签名密钥，由 jwt.secret 派生；轮换密钥会使已有令牌全部失效 */
    private final SecretKey key;

    /**
     * @param properties 提供密钥与有效期
     * @throws io.jsonwebtoken.security.WeakKeyException 密钥长度不足 256bit（HS256 要求），故意在启动期暴露
     */
    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发访问令牌：subject 为用户名，额外携带 uid 与 role，有效期由 jwt.expire-minutes 决定。
     *
     * @param user 已通过密码校验的用户实体
     * @return 紧凑序列化后的 JWT 字符串
     */
    public String issue(AppUser user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(properties.getExpireMinutes() * 60);
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    /** 校验并解析 token；无效/过期返回 empty，由过滤器按匿名请求处理 */
    public Optional<AuthPrincipal> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new AuthPrincipal(
                    claims.get("uid", Number.class).longValue(),
                    claims.getSubject(),
                    Role.valueOf(claims.get("role", String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected JWT: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
