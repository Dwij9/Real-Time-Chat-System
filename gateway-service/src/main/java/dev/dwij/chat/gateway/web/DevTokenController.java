package dev.dwij.chat.gateway.web;

import dev.dwij.chat.common.security.JwtService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Mints a token for a given user id so the demo client and load scripts can connect. Local profile only:
 * in a real deployment the token comes from the identity provider and the gateway only verifies it.
 */
@RestController
@Profile("local")
public class DevTokenController {

    private final JwtService jwtService;

    public DevTokenController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @GetMapping("/dev/token")
    public Map<String, String> token(@RequestParam String userId) {
        return Map.of("userId", userId, "token", jwtService.issue(userId));
    }
}
