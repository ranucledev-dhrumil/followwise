package com.followwise.document;

public record UploadDocumentResult(
        DocumentVersionResponse document,
        boolean isDuplicate
) {
}
