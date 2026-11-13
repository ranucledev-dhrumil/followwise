package com.followwise.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class LocalDocumentStorage implements DocumentStorage {

    private final Path rootDir;

    public LocalDocumentStorage(String localDir) {
        this.rootDir = Paths.get(localDir).toAbsolutePath().normalize();
    }

    private Path resolvePath(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("invalid storage key");
        }
        Path target = rootDir.resolve(key).normalize();
        if (!target.startsWith(rootDir) || target.equals(rootDir)) {
            throw new IllegalArgumentException("path traversal not allowed");
        }
        return target;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        Path target = resolvePath(key);
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new StorageException("Failed to write document locally", e);
        }
    }

    @Override
    public InputStream open(String key) {
        Path target = resolvePath(key);
        if (!Files.exists(target) || Files.isDirectory(target)) {
            throw new StorageFileNotFoundException("File not found: " + key);
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new StorageException("Failed to open document locally", e);
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolvePath(key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new StorageException("Failed to delete document locally", e);
        }
    }
}
