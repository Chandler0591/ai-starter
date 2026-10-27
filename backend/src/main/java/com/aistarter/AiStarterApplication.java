package com.aistarter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 启动入口。{@code @ConfigurationPropertiesScan} 让 config 包下的
 * AiProperties / JwtProperties 无需逐个标 {@code @EnableConfigurationProperties} 即可绑定。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiStarterApplication {

    /**
     * 启动时可附加参数：
     * {@code --eval=true} 为回归门禁模式（跑完评测集即退出，任一条失败则非零码退出）。
     *
     * @param args 标准 Spring Boot 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AiStarterApplication.class, args);
    }
}
