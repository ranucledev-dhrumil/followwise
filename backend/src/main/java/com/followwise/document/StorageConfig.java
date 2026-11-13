package com.followwise.document;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@Configuration
public class StorageConfig {

    private final Environment environment;

    @Value("${storage.provider:local}")
    private String provider = "local";

    @Value("${storage.supabase-url:}")
    private String supabaseUrl = "";

    @Value("${storage.supabase-service-key:}")
    private String supabaseServiceKey = "";

    @Value("${storage.bucket:documents}")
    private String bucket = "documents";

    @Value("${storage.local-dir:/tmp/followwise-storage}")
    private String localDir = "/tmp/followwise-storage";

    @Value("${app.max-document-bytes:4194304}")
    private long maxDocumentBytes = 4194304;

    @Value("${app.max-documents-per-app:1000}")
    private int maxDocumentsPerApp = 1000;

    @Value("${app.max-storage-bytes-per-app:209715200}")
    private long maxStorageBytesPerApp = 209715200;

    @Value("${app.max-storage-bytes-total:943718400}")
    private long maxStorageBytesTotal = 943718400;

    @Value("${app.max-versions-per-request:10}")
    private int maxVersionsPerRequest = 10;

    @Value("${app.max-requests-per-party:50}")
    private int maxRequestsPerParty = 50;

    @Value("${app.max-concurrent-uploads:4}")
    private int maxConcurrentUploads = 4;

    public StorageConfig(Environment environment) {
        this.environment = environment;
    }

    // Setters for programmatic configuration/testing
    public void setProvider(String provider) {
        this.provider = provider;
    }

    public void setSupabaseUrl(String supabaseUrl) {
        this.supabaseUrl = supabaseUrl;
    }

    public void setSupabaseServiceKey(String supabaseServiceKey) {
        this.supabaseServiceKey = supabaseServiceKey;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public void setLocalDir(String localDir) {
        this.localDir = localDir;
    }

    public void setMaxDocumentBytes(long maxDocumentBytes) {
        this.maxDocumentBytes = maxDocumentBytes;
    }

    public void setMaxDocumentsPerApp(int maxDocumentsPerApp) {
        this.maxDocumentsPerApp = maxDocumentsPerApp;
    }

    public void setMaxStorageBytesPerApp(long maxStorageBytesPerApp) {
        this.maxStorageBytesPerApp = maxStorageBytesPerApp;
    }

    public void setMaxStorageBytesTotal(long maxStorageBytesTotal) {
        this.maxStorageBytesTotal = maxStorageBytesTotal;
    }

    public void setMaxVersionsPerRequest(int maxVersionsPerRequest) {
        this.maxVersionsPerRequest = maxVersionsPerRequest;
    }

    public void setMaxRequestsPerParty(int maxRequestsPerParty) {
        this.maxRequestsPerParty = maxRequestsPerParty;
    }

    public void setMaxConcurrentUploads(int maxConcurrentUploads) {
        this.maxConcurrentUploads = maxConcurrentUploads;
    }

    public long getMaxDocumentBytes() {
        return maxDocumentBytes;
    }

    public int getMaxDocumentsPerApp() {
        return maxDocumentsPerApp;
    }

    public long getMaxStorageBytesPerApp() {
        return maxStorageBytesPerApp;
    }

    public long getMaxStorageBytesTotal() {
        return maxStorageBytesTotal;
    }

    public int getMaxVersionsPerRequest() {
        return maxVersionsPerRequest;
    }

    public int getMaxRequestsPerParty() {
        return maxRequestsPerParty;
    }

    public int getMaxConcurrentUploads() {
        return maxConcurrentUploads;
    }

    @PostConstruct
    public void validate() {
        if (!"supabase".equals(provider) && !"local".equals(provider)) {
            throw new IllegalStateException("STORAGE_PROVIDER must be exactly 'supabase' or 'local'");
        }

        if ("local".equals(provider)) {
            String[] activeProfiles = environment != null ? environment.getActiveProfiles() : new String[0];
            boolean isAllowed = Arrays.stream(activeProfiles)
                    .anyMatch(p -> "local".equalsIgnoreCase(p) || "test".equalsIgnoreCase(p));
            if (!isAllowed) {
                throw new IllegalStateException("Local storage provider is only allowed in 'local' or 'test' profile");
            }
        }

        if ("supabase".equals(provider)) {
            if (supabaseUrl == null || supabaseUrl.isBlank()) {
                throw new IllegalStateException("SUPABASE_URL must not be blank when using supabase storage provider");
            }
            if (!supabaseUrl.startsWith("https://")) {
                throw new IllegalStateException("SUPABASE_URL must start with 'https://'");
            }
            if (supabaseServiceKey == null || supabaseServiceKey.isBlank()) {
                throw new IllegalStateException("SUPABASE_SERVICE_KEY must not be blank when using supabase storage provider");
            }
            if (bucket == null || bucket.isBlank()) {
                throw new IllegalStateException("STORAGE_BUCKET must not be blank when using supabase storage provider");
            }
        }

        if (maxDocumentBytes <= 0) {
            throw new IllegalStateException("MAX_DOCUMENT_BYTES must be positive");
        }
        if (maxDocumentsPerApp <= 0) {
            throw new IllegalStateException("MAX_DOCUMENTS_PER_APP must be positive");
        }
        if (maxStorageBytesPerApp <= 0) {
            throw new IllegalStateException("MAX_STORAGE_BYTES_PER_APP must be positive");
        }
        if (maxStorageBytesTotal <= 0) {
            throw new IllegalStateException("MAX_STORAGE_BYTES_TOTAL must be positive");
        }
        if (maxVersionsPerRequest <= 0) {
            throw new IllegalStateException("MAX_VERSIONS_PER_REQUEST must be positive");
        }
        if (maxRequestsPerParty <= 0) {
            throw new IllegalStateException("MAX_REQUESTS_PER_PARTY must be positive");
        }
        if (maxConcurrentUploads <= 0) {
            throw new IllegalStateException("MAX_CONCURRENT_UPLOADS must be positive");
        }
    }

    @Bean
    @ConditionalOnMissingBean(DocumentStorage.class)
    public DocumentStorage documentStorage() {
        if ("supabase".equals(provider)) {
            return new SupabaseDocumentStorage(supabaseUrl, supabaseServiceKey, bucket);
        }
        return new LocalDocumentStorage(localDir);
    }
}
