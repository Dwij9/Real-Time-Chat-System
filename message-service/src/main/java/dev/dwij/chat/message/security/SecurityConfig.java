package dev.dwij.chat.message.security;

import dev.dwij.chat.common.security.JwtService;
import dev.dwij.chat.message.config.MessageProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class SecurityConfig {

    @Bean
    public JwtService jwtService(MessageProperties properties) {
        return new JwtService(properties.getJwtSecret(), Duration.ofHours(12));
    }
}
