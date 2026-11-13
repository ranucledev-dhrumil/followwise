package com.followwise.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalDocumentStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void put_open_delete_round_trip() throws Exception {
        LocalDocumentStorage storage = new LocalDocumentStorage(tempDir.toString());
        String key = "workspace1/app1/doc1.pdf";
        byte[] content = "%PDF-1.4 test document content".getBytes(StandardCharsets.UTF_8);

        storage.put(key, content, "application/pdf");

        try (InputStream in = storage.open(key)) {
            byte[] read = in.readAllBytes();
            assertArrayEquals(content, read);
        }

        storage.delete(key);

        assertThrows(StorageFileNotFoundException.class, () -> storage.open(key));
    }

    @Test
    void path_traversal_in_key_is_refused() {
        LocalDocumentStorage storage = new LocalDocumentStorage(tempDir.toString());

        assertThrows(IllegalArgumentException.class, () ->
                storage.put("../secret.txt", new byte[]{1}, "application/pdf"));

        assertThrows(IllegalArgumentException.class, () ->
                storage.put("../../etc/passwd", new byte[]{1}, "application/pdf"));

        assertThrows(IllegalArgumentException.class, () ->
                storage.put("..", new byte[]{1}, "application/pdf"));

        assertThrows(IllegalArgumentException.class, () ->
                storage.open("../secret.txt"));

        assertThrows(IllegalArgumentException.class, () ->
                storage.delete("../secret.txt"));
    }
}
