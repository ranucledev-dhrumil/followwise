package com.followwise.document;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class DocumentExceptionHandler {

    @ExceptionHandler(DocumentValidationException.class)
    public ProblemDetail handleValidationException(DocumentValidationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed");
        pd.setTitle("Unprocessable Entity");
        pd.setProperty("errors", ex.getErrors());
        return pd;
    }

    @ExceptionHandler(DocumentConflictException.class)
    public ProblemDetail handleConflictException(DocumentConflictException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create(ex.getConflictType()));
        pd.setTitle("Conflict");
        if (ex.getCurrentVersion() != null) {
            pd.setProperty("currentVersion", ex.getCurrentVersion());
        }
        return pd;
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ProblemDetail handleNotFoundException(DocumentNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Not Found");
        return pd;
    }

    @ExceptionHandler(DocumentBadRequestException.class)
    public ProblemDetail handleBadRequestException(DocumentBadRequestException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Bad Request");
        return pd;
    }

    @ExceptionHandler({StorageFileNotFoundException.class, DocumentGoneException.class})
    public ProblemDetail handleGoneException(RuntimeException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, "gone");
        pd.setTitle("Gone");
        return pd;
    }

    @ExceptionHandler(StorageException.class)
    public ProblemDetail handleStorageException(StorageException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "storage_unavailable");
        pd.setTitle("Service Unavailable");
        return pd;
    }

    @ExceptionHandler(DocumentUploadBusyException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> handleUploadBusyException(DocumentUploadBusyException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "busy");
        pd.setTitle("Too Many Requests");
        return org.springframework.http.ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "5")
                .body(pd);
    }
}
