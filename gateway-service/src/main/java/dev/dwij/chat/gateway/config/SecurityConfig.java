package dev.dwij.chat.gateway.config;

import dev.dwij.chat.common.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class SecurityConfig {

    @Bean
    public JwtService jwtService(GatewayProperties properties) {
        return new JwtService(properties.getJwtSecret(), Duration.ofHours(12));
    }
}
