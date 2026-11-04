package com.followwise.app;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class BodySizeFilter extends OncePerRequestFilter {

    public static final int MAX_SIZE = 81920;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        boolean isTarget = (method.equalsIgnoreCase("POST") && uri.matches("^/api/workspaces/[^/]+/apps/?$"))
                || (method.equalsIgnoreCase("PUT") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/definition/?$"));

        if (!isTarget) {
            filterChain.doFilter(request, response);
            return;
        }

        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_SIZE) {
            write413(response);
            return;
        }

        BoundedSizeRequestWrapper wrappedRequest = new BoundedSizeRequestWrapper(request, MAX_SIZE);
        try {
            filterChain.doFilter(wrappedRequest, response);
        } catch (Exception e) {
            if (isPayloadSizeExceeded(e)) {
                if (!response.isCommitted()) {
                    write413(response);
                }
            } else {
                throw e;
            }
        }
    }

    private boolean isPayloadSizeExceeded(Throwable t) {
        while (t != null) {
            if (t instanceof MaxPayloadSizeExceededException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    public static void write413(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Payload Too Large\",\"status\":413,\"detail\":\"Request body exceeds maximum size of 81920 bytes\"}");
    }

    private static class BoundedSizeRequestWrapper extends HttpServletRequestWrapper {
        private final int maxBytes;
        private CountingServletInputStream stream;

        public BoundedSizeRequestWrapper(HttpServletRequest request, int maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingServletInputStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            if (encoding == null) {
                encoding = StandardCharsets.UTF_8.name();
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
        }
    }

    private static class CountingServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final int maxBytes;
        private int bytesRead = 0;

        public CountingServletInputStream(ServletInputStream delegate, int maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                bytesRead++;
                if (bytesRead > maxBytes) {
                    throw new MaxPayloadSizeExceededException("Request body exceeds maximum size of " + maxBytes + " bytes");
                }
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                bytesRead += n;
                if (bytesRead > maxBytes) {
                    throw new MaxPayloadSizeExceededException("Request body exceeds maximum size of " + maxBytes + " bytes");
                }
            }
            return n;
        }
    }
}
