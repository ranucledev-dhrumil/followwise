package com.followwise.document;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class StorageConfigTest {

    @Test
    void provider_validation_invalid_provider_fails() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        StorageConfig config = new StorageConfig(env);
        config.setProvider("s3");

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::validate);
        assertEquals("STORAGE_PROVIDER must be exactly 'supabase' or 'local'", ex.getMessage());
    }

    @Test
    void local_provider_outside_local_or_test_profile_fails() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        StorageConfig config = new StorageConfig(env);
        config.setProvider("local");

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::validate);
        assertEquals("Local storage provider is only allowed in 'local' or 'test' profile", ex.getMessage());
    }

    @Test
    void local_provider_with_local_profile_succeeds() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        StorageConfig config = new StorageConfig(env);
        config.setProvider("local");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void local_provider_with_test_profile_succeeds() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        StorageConfig config = new StorageConfig(env);
        config.setProvider("local");
        assertDoesNotThrow(config::validate);
    }

    @Test
    void supabase_provider_missing_url_fails() {
        MockEnvironment env = new MockEnvironment();
        StorageConfig config = new StorageConfig(env);
        config.setProvider("supabase");
        config.setSupabaseUrl("");
        config.setSupabaseServiceKey("key");
        config.setBucket("documents");

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::validate);
        assertEquals("SUPABASE_URL must not be blank when using supabase storage provider", ex.getMessage());
    }

    @Test
    void supabase_provider_non_https_url_fails() {
        MockEnvironment env = new MockEnvironment();
        StorageConfig config = new StorageConfig(env);
        config.setProvider("supabase");
        config.setSupabaseUrl("http://myproject.supabase.co");
        config.setSupabaseServiceKey("key");
        config.setBucket("documents");

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::validate);
        assertEquals("SUPABASE_URL must start with 'https://'", ex.getMessage());
    }

    @Test
    void supabase_provider_missing_key_fails() {
        MockEnvironment env = new MockEnvironment();
        StorageConfig config = new StorageConfig(env);
        config.setProvider("supabase");
        config.setSupabaseUrl("https://myproject.supabase.co");
        config.setSupabaseServiceKey("   ");
        config.setBucket("documents");

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::validate);
        assertEquals("SUPABASE_SERVICE_KEY must not be blank when using supabase storage provider", ex.getMessage());
    }

    @Test
    void supabase_provider_missing_bucket_fails() {
        MockEnvironment env = new MockEnvironment();
        StorageConfig config = new StorageConfig(env);
        config.setProvider("supabase");
        config.setSupabaseUrl("https://myproject.supabase.co");
        config.setSupabaseServiceKey("secret-key");
        config.setBucket("");

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::validate);
        assertEquals("STORAGE_BUCKET must not be blank when using supabase storage provider", ex.getMessage());
    }

    @Test
    void supabase_provider_valid_config_succeeds() {
        MockEnvironment env = new MockEnvironment();
        StorageConfig config = new StorageConfig(env);
        config.setProvider("supabase");
        config.setSupabaseUrl("https://myproject.supabase.co");
        config.setSupabaseServiceKey("sb_secret_test123");
        config.setBucket("documents");

        assertDoesNotThrow(config::validate);
        assertNotNull(config.documentStorage());
        assertInstanceOf(SupabaseDocumentStorage.class, config.documentStorage());
    }

    @Test
    void numeric_settings_validation_failures() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");

        StorageConfig config = new StorageConfig(env);
        config.setProvider("local");

        config.setMaxDocumentBytes(0);
        assertEquals("MAX_DOCUMENT_BYTES must be positive",
                assertThrows(IllegalStateException.class, config::validate).getMessage());
        config.setMaxDocumentBytes(4194304);

        config.setMaxDocumentsPerApp(0);
        assertEquals("MAX_DOCUMENTS_PER_APP must be positive",
                assertThrows(IllegalStateException.class, config::validate).getMessage());
        config.setMaxDocumentsPerApp(1000);

        config.setMaxStorageBytesPerApp(0);
        assertEquals("MAX_STORAGE_BYTES_PER_APP must be positive",
                assertThrows(IllegalStateException.class, config::validate).getMessage());
        config.setMaxStorageBytesPerApp(209715200);

        config.setMaxStorageBytesTotal(0);
        assertEquals("MAX_STORAGE_BYTES_TOTAL must be positive",
                assertThrows(IllegalStateException.class, config::validate).getMessage());
        config.setMaxStorageBytesTotal(943718400);

        config.setMaxVersionsPerRequest(0);
        assertEquals("MAX_VERSIONS_PER_REQUEST must be positive",
                assertThrows(IllegalStateException.class, config::validate).getMessage());
        config.setMaxVersionsPerRequest(10);

        config.setMaxRequestsPerParty(0);
        assertEquals("MAX_REQUESTS_PER_PARTY must be positive",
                assertThrows(IllegalStateException.class, config::validate).getMessage());
        config.setMaxRequestsPerParty(50);

        config.setMaxConcurrentUploads(0);
        assertEquals("MAX_CONCURRENT_UPLOADS must be positive",
                assertThrows(IllegalStateException.class, config::validate).getMessage());
        config.setMaxConcurrentUploads(4);

        assertDoesNotThrow(config::validate);
    }
}
