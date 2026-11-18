package com.followwise.document;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FakeDocumentStorage implements DocumentStorage {

    private final Map<String, byte[]> storage = new ConcurrentHashMap<>();
    private final Map<String, String> contentTypes = new ConcurrentHashMap<>();
    private volatile boolean failing = false;
    private volatile boolean deleteFailing = false;
    private volatile java.util.concurrent.CountDownLatch putEnteredLatch = null;
    private volatile java.util.concurrent.CountDownLatch putBlockLatch = null;
    private volatile java.util.concurrent.CountDownLatch deleteEnteredLatch = null;
    private volatile java.util.concurrent.CountDownLatch deleteBlockLatch = null;
    private volatile java.util.concurrent.CountDownLatch openEnteredLatch = null;
    private volatile java.util.concurrent.CountDownLatch openBlockLatch = null;
    private volatile String openBlockKey = null;

    public void setPutLatches(java.util.concurrent.CountDownLatch entered, java.util.concurrent.CountDownLatch block) {
        this.putEnteredLatch = entered;
        this.putBlockLatch = block;
    }

    public void setDeleteLatches(java.util.concurrent.CountDownLatch entered, java.util.concurrent.CountDownLatch block) {
        this.deleteEnteredLatch = entered;
        this.deleteBlockLatch = block;
    }

    public void setOpenLatches(String targetKey, java.util.concurrent.CountDownLatch entered, java.util.concurrent.CountDownLatch block) {
        this.openBlockKey = targetKey;
        this.openEnteredLatch = entered;
        this.openBlockLatch = block;
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public void setDeleteFailing(boolean deleteFailing) {
        this.deleteFailing = deleteFailing;
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
        deleteFailing = false;
        putEnteredLatch = null;
        putBlockLatch = null;
        deleteEnteredLatch = null;
        deleteBlockLatch = null;
        openEnteredLatch = null;
        openBlockLatch = null;
        openBlockKey = null;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        if (failing) {
            throw new StorageException("Simulated storage failure on put");
        }
        if (putEnteredLatch != null) {
            putEnteredLatch.countDown();
        }
        if (putBlockLatch != null) {
            try {
                putBlockLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StorageException("Interrupted during fake storage put wait", e);
            }
        }
        storage.put(key, bytes != null ? bytes.clone() : new byte[0]);
        contentTypes.put(key, contentType != null ? contentType : "application/octet-stream");
    }

    @Override
    public InputStream open(String key) {
        if (failing) {
            throw new StorageException("Simulated storage failure on open");
        }
        if (openEnteredLatch != null && (openBlockKey == null || openBlockKey.equals(key))) {
            openEnteredLatch.countDown();
        }
        if (openBlockLatch != null && (openBlockKey == null || openBlockKey.equals(key))) {
            try {
                openBlockLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StorageException("Interrupted during fake storage open wait", e);
            }
        }
        byte[] bytes = storage.get(key);
        if (bytes == null) {
            throw new StorageFileNotFoundException("Object not found: " + key);
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void delete(String key) {
        if (failing || deleteFailing) {
            throw new StorageException("Simulated storage failure on delete");
        }
        if (deleteEnteredLatch != null) {
            deleteEnteredLatch.countDown();
        }
        if (deleteBlockLatch != null) {
            try {
                deleteBlockLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StorageException("Interrupted during fake storage delete wait", e);
            }
        }
        storage.remove(key);
        contentTypes.remove(key);
    }
}
