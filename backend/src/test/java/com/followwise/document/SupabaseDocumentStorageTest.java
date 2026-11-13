package com.followwise.document;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class SupabaseDocumentStorageTest {

    private HttpServer server;
    private int port;
    private final List<RecordedRequest> recordedRequests = Collections.synchronizedList(new ArrayList<>());

    record RecordedRequest(String method, String path, String apikey, String authorization, String contentType, String xUpsert, byte[] body) {}

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        recordedRequests.clear();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void upload_contract_with_sb_secret_key() {
        server.createContext("/storage/v1/object/documents/w1/a1/d1", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            recordedRequests.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("apikey"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("x-upsert"),
                    body
            ));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        String serviceKey = "sb_secret_test123456789";
        SupabaseDocumentStorage storage = new SupabaseDocumentStorage(
                "http://localhost:" + port, serviceKey, "documents");

        byte[] payload = "%PDF-1.4 test data".getBytes(StandardCharsets.UTF_8);
        storage.put("w1/a1/d1", payload, "application/pdf");

        assertEquals(1, recordedRequests.size());
        RecordedRequest req = recordedRequests.get(0);
        assertEquals("POST", req.method());
        assertEquals("/storage/v1/object/documents/w1/a1/d1", req.path());
        assertEquals(serviceKey, req.apikey());
        assertNull(req.authorization(), "sb_secret_ key must NOT send Authorization header");
        assertEquals("application/pdf", req.contentType());
        assertEquals("false", req.xUpsert());
        assertArrayEquals(payload, req.body());
    }

    @Test
    void upload_contract_with_legacy_jwt_key() {
        server.createContext("/storage/v1/object/documents/w1/a1/d1", exchange -> {
            recordedRequests.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("apikey"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("x-upsert"),
                    exchange.getRequestBody().readAllBytes()
            ));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        String jwtKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.dummyPayload";
        SupabaseDocumentStorage storage = new SupabaseDocumentStorage(
                "http://localhost:" + port, jwtKey, "documents");

        storage.put("w1/a1/d1", new byte[]{1, 2, 3}, "image/png");

        assertEquals(1, recordedRequests.size());
        RecordedRequest req = recordedRequests.get(0);
        assertEquals(jwtKey, req.apikey());
        assertEquals("Bearer " + jwtKey, req.authorization(), "Legacy JWT must send Authorization: Bearer header");
    }

    @Test
    void download_contract_and_one_retry_on_500() throws Exception {
        AtomicInteger count = new AtomicInteger(0);
        byte[] expectedContent = "%PDF-1.4 downloaded bytes".getBytes(StandardCharsets.UTF_8);

        server.createContext("/storage/v1/object/authenticated/documents/w1/a1/d1", exchange -> {
            recordedRequests.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("apikey"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("x-upsert"),
                    null
            ));
            if (count.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(500, 0);
                exchange.getResponseBody().write("error".getBytes());
                exchange.close();
            } else {
                exchange.sendResponseHeaders(200, expectedContent.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(expectedContent);
                }
                exchange.close();
            }
        });
        server.start();

        String serviceKey = "sb_secret_retry_key";
        SupabaseDocumentStorage storage = new SupabaseDocumentStorage(
                "http://localhost:" + port, serviceKey, "documents");

        try (InputStream in = storage.open("w1/a1/d1")) {
            byte[] read = in.readAllBytes();
            assertArrayEquals(expectedContent, read);
        }

        // Exactly 2 attempts (1 failure + 1 retry)
        assertEquals(2, count.get());
        assertEquals(2, recordedRequests.size());
        RecordedRequest req = recordedRequests.get(0);
        assertEquals("GET", req.method());
        assertEquals("/storage/v1/object/authenticated/documents/w1/a1/d1", req.path());
        assertEquals(serviceKey, req.apikey());
    }

    @Test
    void delete_contract_and_one_retry() {
        AtomicInteger count = new AtomicInteger(0);

        server.createContext("/storage/v1/object/documents/w1/a1/d1", exchange -> {
            recordedRequests.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("apikey"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    null, null, null
            ));
            if (count.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(502, -1);
                exchange.close();
            } else {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            }
        });
        server.start();

        String serviceKey = "sb_secret_delete_key";
        SupabaseDocumentStorage storage = new SupabaseDocumentStorage(
                "http://localhost:" + port, serviceKey, "documents");

        storage.delete("w1/a1/d1");

        assertEquals(2, count.get());
        assertEquals(2, recordedRequests.size());
        assertEquals("DELETE", recordedRequests.get(0).method());
        assertEquals("/storage/v1/object/documents/w1/a1/d1", recordedRequests.get(0).path());
    }

    @Test
    void put_has_no_retry_and_key_not_in_exception() {
        AtomicInteger count = new AtomicInteger(0);

        server.createContext("/storage/v1/object/documents/w1/a1/d1", exchange -> {
            count.incrementAndGet();
            exchange.sendResponseHeaders(500, 0);
            exchange.getResponseBody().write("server error".getBytes());
            exchange.close();
        });
        server.start();

        String superSecretKey = "sb_secret_CRITICAL_MARKER_999";
        SupabaseDocumentStorage storage = new SupabaseDocumentStorage(
                "http://localhost:" + port, superSecretKey, "documents");

        StorageException ex = assertThrows(StorageException.class, () ->
                storage.put("w1/a1/d1", new byte[]{1}, "application/pdf"));

        // Exactly 1 attempt (NO retry on PUT)
        assertEquals(1, count.get());

        // Key must never appear in exception message
        assertFalse(ex.getMessage().contains(superSecretKey));
        if (ex.getCause() != null) {
            assertFalse(ex.getCause().getMessage().contains(superSecretKey));
        }
    }

    @Test
    void client_timeouts_and_log_hygiene_on_failure(CapturedOutput output) throws Exception {
        String testKey = "sb_secret_MY_KEY_SECRET_MARKER_123";
        SupabaseDocumentStorage storage = new SupabaseDocumentStorage(
                "http://localhost:" + port, testKey, "documents");

        // 1. Assert connect timeout is 5 seconds
        Field clientField = SupabaseDocumentStorage.class.getDeclaredField("httpClient");
        clientField.setAccessible(true);
        HttpClient client = (HttpClient) clientField.get(storage);
        assertNotNull(client);
        assertEquals(Duration.ofSeconds(5), client.connectTimeout().orElse(null),
                "HttpClient connect timeout must be 5 seconds");

        // 2. Assert request timeout is 30 seconds
        Method reqBuilderMethod = SupabaseDocumentStorage.class.getDeclaredMethod("newRequestBuilder", URI.class);
        reqBuilderMethod.setAccessible(true);
        HttpRequest.Builder builder = (HttpRequest.Builder) reqBuilderMethod.invoke(storage, URI.create("http://localhost:" + port + "/test"));
        HttpRequest req = builder.GET().build();
        assertEquals(Duration.ofSeconds(30), req.timeout().orElse(null),
                "HttpRequest timeout must be 30 seconds");

        // 3. Failing storage calls (put, open, delete) with marker
        server.createContext("/storage/v1/object/documents/w1/a1/d1", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.getResponseBody().write("storage error SECRET_MARKER_123".getBytes());
            exchange.close();
        });
        server.createContext("/storage/v1/object/authenticated/documents/w1/a1/d1", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.getResponseBody().write("storage error SECRET_MARKER_123".getBytes());
            exchange.close();
        });
        server.start();

        // Put failure
        StorageException putEx = assertThrows(StorageException.class, () ->
                storage.put("w1/a1/d1", "%PDF-1.4 test".getBytes(StandardCharsets.UTF_8), "application/pdf"));
        assertFalse(putEx.getMessage().contains(testKey));
        assertFalse(putEx.getMessage().contains("SECRET_MARKER_123"));

        // Open failure
        StorageException openEx = assertThrows(StorageException.class, () ->
                storage.open("w1/a1/d1"));
        assertFalse(openEx.getMessage().contains(testKey));
        assertFalse(openEx.getMessage().contains("SECRET_MARKER_123"));

        // Delete failure
        StorageException delEx = assertThrows(StorageException.class, () ->
                storage.delete("w1/a1/d1"));
        assertFalse(delEx.getMessage().contains(testKey));
        assertFalse(delEx.getMessage().contains("SECRET_MARKER_123"));

        // 4. Assert captured logs contain neither the key nor SECRET_MARKER_123
        String captured = output.getAll();
        assertFalse(captured.contains(testKey), "Captured logs must not contain secret key");
        assertFalse(captured.contains("SECRET_MARKER_123"), "Captured logs must not contain SECRET_MARKER_123");
    }
}
