package dev.dwij.chat.message.security;

import dev.dwij.chat.common.security.JwtService;
import dev.dwij.chat.common.security.TokenException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resolves the caller from the bearer token and exposes it to controllers as a request attribute. Same
 * token the gateway accepts on the WebSocket handshake, so a client holds exactly one credential.
 */
@Component
@Order(1)
public class TokenFilter extends OncePerRequestFilter {

    public static final String CALLER = "chat.caller";

    private final JwtService jwtService;

    public TokenFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        try {
            request.setAttribute(CALLER, jwtService.verify(token));
        } catch (TokenException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }
}
