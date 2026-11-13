package com.followwise.document;

import java.io.InputStream;

public record DocumentDownload(
        String filename,
        String contentType,
        long sizeBytes,
        InputStream stream
) {
}
