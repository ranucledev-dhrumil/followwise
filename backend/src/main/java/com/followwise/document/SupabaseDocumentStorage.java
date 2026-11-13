package com.followwise.document;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class SupabaseDocumentStorage implements DocumentStorage {

    private final String baseUrl;
    private final String serviceKey;
    private final String bucket;
    private final HttpClient httpClient;

    public SupabaseDocumentStorage(String supabaseUrl, String serviceKey, String bucket) {
        this(supabaseUrl, serviceKey, bucket, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    public SupabaseDocumentStorage(String supabaseUrl, String serviceKey, String bucket, HttpClient httpClient) {
        String base = supabaseUrl != null ? supabaseUrl.trim() : "";
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.serviceKey = serviceKey != null ? serviceKey.trim() : "";
        this.bucket = bucket != null ? bucket.trim() : "";
        this.httpClient = httpClient;
    }

    private HttpRequest.Builder newRequestBuilder(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(30))
                .header("apikey", serviceKey);

        if (serviceKey.startsWith("eyJ")) {
            builder.header("Authorization", "Bearer " + serviceKey);
        }
        return builder;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        URI uri = URI.create(baseUrl + "/storage/v1/object/" + bucket + "/" + key);
        String ct = (contentType != null && !contentType.isBlank()) ? contentType : "application/octet-stream";

        HttpRequest request = newRequestBuilder(uri)
                .header("Content-Type", ct)
                .header("x-upsert", "false")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes != null ? bytes : new byte[0]))
                .build();

        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new StorageException("Supabase upload failed with status " + status);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StorageException("Supabase upload failed due to I/O error", e);
        }
    }

    @Override
    public InputStream open(String key) {
        URI uri = URI.create(baseUrl + "/storage/v1/object/authenticated/" + bucket + "/" + key);
        HttpRequest request = newRequestBuilder(uri)
                .GET()
                .build();

        // 1 retry on GET on I/O error or 5xx
        int attempts = 0;
        while (attempts < 2) {
            attempts++;
            try {
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status == 404) {
                    throw new StorageFileNotFoundException("Object not found in Supabase storage: " + key);
                }
                if (status >= 200 && status < 300) {
                    return response.body();
                }
                if (status >= 500 && attempts < 2) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new StorageException("Interrupted during retry wait", ie);
                    }
                    continue;
                }
                throw new StorageException("Supabase download failed with status " + status);
            } catch (StorageFileNotFoundException e) {
                throw e;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new StorageException("Interrupted during Supabase download", e);
                }
                if (attempts < 2) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new StorageException("Interrupted during retry wait", ie);
                    }
                    continue;
                }
                throw new StorageException("Supabase download failed due to I/O error", e);
            }
        }
        throw new StorageException("Supabase download failed after retries");
    }

    @Override
    public void delete(String key) {
        URI uri = URI.create(baseUrl + "/storage/v1/object/" + bucket + "/" + key);
        HttpRequest request = newRequestBuilder(uri)
                .DELETE()
                .build();

        int attempts = 0;
        while (attempts < 2) {
            attempts++;
            try {
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if ((status >= 200 && status < 300) || status == 404) {
                    return;
                }
                if (status >= 500 && attempts < 2) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new StorageException("Interrupted during retry wait", ie);
                    }
                    continue;
                }
                throw new StorageException("Supabase delete failed with status " + status);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new StorageException("Interrupted during Supabase delete", e);
                }
                if (attempts < 2) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new StorageException("Interrupted during retry wait", ie);
                    }
                    continue;
                }
                throw new StorageException("Supabase delete failed due to I/O error", e);
            }
        }
    }
}
