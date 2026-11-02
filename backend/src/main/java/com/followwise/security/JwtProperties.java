package com.followwise.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class JwtProperties {

    @Value("${jwt.secret:}")
    private String secret;

    @Value("${jwt.access-ttl-minutes:15}")
    private long accessTtlMinutes;

    @Value("${jwt.refresh-ttl-days:30}")
    private long refreshTtlDays;

    @Value("${auth.cookie-secure:}")
    private String cookieSecureRaw;

    private boolean cookieSecure;

    @PostConstruct
    public void validate() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 UTF-8 bytes");
        }
        if (!"true".equals(cookieSecureRaw) && !"false".equals(cookieSecureRaw)) {
            throw new IllegalStateException("AUTH_COOKIE_SECURE must be exactly 'true' or 'false'");
        }
        this.cookieSecure = Boolean.parseBoolean(cookieSecureRaw);
    }

    public String getSecret() {
        return secret;
    }

    public long getAccessTtlMinutes() {
        return accessTtlMinutes;
    }

    public long getRefreshTtlDays() {
        return refreshTtlDays;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }
}
