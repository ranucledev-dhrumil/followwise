package com.followwise.document;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FakeDocumentStorage implements DocumentStorage {

    private final Map<String, byte[]> storage = new ConcurrentHashMap<>();
    private final Map<String, String> contentTypes = new ConcurrentHashMap<>();
    private volatile boolean failing = false;

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public boolean isFailing() {
        return failing;
    }

    public boolean containsKey(String key) {
        return storage.containsKey(key);
    }

    public int size() {
        return storage.size();
    }

    public void clear() {
        storage.clear();
        contentTypes.clear();
        failing = false;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        if (failing) {
            throw new StorageException("Simulated storage failure on put");
        }
        storage.put(key, bytes != null ? bytes.clone() : new byte[0]);
        contentTypes.put(key, contentType != null ? contentType : "application/octet-stream");
    }

    @Override
    public InputStream open(String key) {
        if (failing) {
            throw new StorageException("Simulated storage failure on open");
        }
        byte[] bytes = storage.get(key);
        if (bytes == null) {
            throw new StorageFileNotFoundException("Object not found: " + key);
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void delete(String key) {
        if (failing) {
            throw new StorageException("Simulated storage failure on delete");
        }
        storage.remove(key);
        contentTypes.remove(key);
    }
}
