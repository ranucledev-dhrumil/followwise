package com.followwise.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NoStoreFilter extends OncePerRequestFilter {

    private static final Pattern TARGET_PATH = Pattern.compile("^/api/workspaces/[^/]+/apps/[^/]+/(forms|dashboard)(/.*)?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri != null && TARGET_PATH.matcher(uri).matches()) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            HttpServletResponseWrapper wrapper = new HttpServletResponseWrapper(response) {
                @Override
                public void setHeader(String name, String value) {
                    if (HttpHeaders.CACHE_CONTROL.equalsIgnoreCase(name)) {
                        super.setHeader(name, "private, no-store");
                    } else {
                        super.setHeader(name, value);
                    }
                }

                @Override
                public void addHeader(String name, String value) {
                    if (HttpHeaders.CACHE_CONTROL.equalsIgnoreCase(name)) {
                        super.setHeader(name, "private, no-store");
                    } else {
                        super.addHeader(name, value);
                    }
                }
            };
            try {
                filterChain.doFilter(request, wrapper);
            } finally {
                response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            }
        } else {
            filterChain.doFilter(request, response);
        }
    }
}
