package com.followwise.document;

import java.io.InputStream;

public interface DocumentStorage {
    void put(String key, byte[] bytes, String contentType);
    InputStream open(String key);
    void delete(String key);
}
