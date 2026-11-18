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
    public static final int DATA_MAX_SIZE = 32768;
    public static final int LEDGER_MAX_SIZE = 8192;
    public static final int DOCUMENT_JSON_MAX_SIZE = 8192;

    @org.springframework.beans.factory.annotation.Value("${app.max-document-bytes:4194304}")
    private long maxDocumentBytes = 4194304;

    public void setMaxDocumentBytes(long maxDocumentBytes) {
        this.maxDocumentBytes = maxDocumentBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        long limit = 0;
        boolean isAppTarget = (method.equalsIgnoreCase("POST") && uri.matches("^/api/workspaces/[^/]+/apps/?$"))
                || (method.equalsIgnoreCase("PUT") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/definition/?$"));

        boolean isDataTarget = (method.equalsIgnoreCase("POST") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/parties/?$"))
                || (method.equalsIgnoreCase("PUT") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/parties/[^/]+/?$"))
                || (method.equalsIgnoreCase("POST") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/records/?$"))
                || (method.equalsIgnoreCase("PUT") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/records/[^/]+/?$"));

        boolean isLedgerTarget = (method.equalsIgnoreCase("POST") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/parties/[^/]+/charges/?$"))
                || (method.equalsIgnoreCase("POST") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/charges/[^/]+/payments/?$"))
                || (method.equalsIgnoreCase("POST") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/ledger/[^/]+/reverse/?$"));

        boolean isGenerateTarget = method.equalsIgnoreCase("POST")
                && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/charges/generate/?$");

        boolean isDocumentUploadTarget = method.equalsIgnoreCase("POST")
                && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/document-requests/[^/]+/documents/?$");

        boolean isDocumentJsonTarget = (method.equalsIgnoreCase("POST") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/parties/[^/]+/document-requests/?$"))
                || ((method.equalsIgnoreCase("POST") || method.equalsIgnoreCase("PUT")) && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/document-requests/[^/]+/due-date/?$"))
                || (method.equalsIgnoreCase("POST") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/document-requests/[^/]+/approve/?$"))
                || (method.equalsIgnoreCase("POST") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/document-requests/[^/]+/reject/?$"))
                || (method.equalsIgnoreCase("POST") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/document-requests/[^/]+/cancel/?$"))
                || (method.equalsIgnoreCase("POST") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/documents/[^/]+/purge/?$"))
                || (method.equalsIgnoreCase("POST") && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/document-requests/[^/]+/documents/[^/]+/purge/?$"));

        boolean isReminderTarget = method.equalsIgnoreCase("POST")
                && uri.matches("^/api/workspaces/[^/]+/apps/[^/]+/parties/[^/]+/reminders/(pause|resume)/?$");

        if (isDocumentUploadTarget) {
            limit = maxDocumentBytes;
        } else if (isDocumentJsonTarget || isReminderTarget) {
            limit = DOCUMENT_JSON_MAX_SIZE;
        } else if (isGenerateTarget) {
            limit = 1024;
        } else if (isAppTarget) {
            limit = MAX_SIZE;
        } else if (isDataTarget) {
            limit = DATA_MAX_SIZE;
        } else if (isLedgerTarget) {
            limit = LEDGER_MAX_SIZE;
        } else {
            filterChain.doFilter(request, response);
            return;
        }

        long contentLength = request.getContentLengthLong();
        if (contentLength > limit) {
            write413(response, limit);
            return;
        }

        BoundedSizeRequestWrapper wrappedRequest = new BoundedSizeRequestWrapper(request, limit);
        try {
            filterChain.doFilter(wrappedRequest, response);
        } catch (Exception e) {
            if (isPayloadSizeExceeded(e)) {
                if (!response.isCommitted()) {
                    write413(response, limit);
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
        write413(response, MAX_SIZE);
    }

    public static void write413(HttpServletResponse response, long limit) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Payload Too Large\",\"status\":413,\"detail\":\"Request body exceeds maximum size of " + limit + " bytes\"}");
    }

    private static class BoundedSizeRequestWrapper extends HttpServletRequestWrapper {
        private final long maxBytes;
        private CountingServletInputStream stream;

        public BoundedSizeRequestWrapper(HttpServletRequest request, long maxBytes) {
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
        private final long maxBytes;
        private long bytesRead = 0;

        public CountingServletInputStream(ServletInputStream delegate, long maxBytes) {
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
