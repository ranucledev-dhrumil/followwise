package com.followwise.document;

public class DocumentUploadBusyException extends RuntimeException {
    public DocumentUploadBusyException() {
        super("busy");
    }
}
