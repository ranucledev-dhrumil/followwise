package com.followwise.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class ClientOriginCheckFilter extends OncePerRequestFilter {

    private final String appBaseUrl;

    public ClientOriginCheckFilter(@Value("${app.base-url:}") String appBaseUrl) {
        this.appBaseUrl = appBaseUrl;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if ("POST".equalsIgnoreCase(method) && path != null &&
                (path.endsWith("/api/auth/refresh") || path.endsWith("/api/auth/logout"))) {
            String clientHeader = request.getHeader("X-FollowWise-Client");
            if (!"web".equalsIgnoreCase(clientHeader)) {
                sendProblemDetail(response, HttpStatus.FORBIDDEN, "Missing or invalid X-FollowWise-Client header");
                return;
            }

            String originHeader = request.getHeader("Origin");
            if (originHeader != null && !originHeader.isBlank()) {
                if (appBaseUrl != null && !appBaseUrl.isBlank() && !appBaseUrl.equalsIgnoreCase(originHeader.trim())) {
                    sendProblemDetail(response, HttpStatus.FORBIDDEN, "Origin header does not match expected application URL");
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private void sendProblemDetail(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = String.format("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"}",
                status.getReasonPhrase(), status.value(), detail);
        response.getWriter().write(body);
    }
}
