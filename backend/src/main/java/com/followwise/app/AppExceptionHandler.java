package com.followwise.app;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class AppExceptionHandler {

    @ExceptionHandler(AppValidationException.class)
    public ProblemDetail handleValidationException(AppValidationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed");
        pd.setTitle("Unprocessable Entity");
        pd.setProperty("errors", ex.getErrors());
        return pd;
    }

    @ExceptionHandler(AppConflictException.class)
    public ProblemDetail handleConflictException(AppConflictException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create(ex.getConflictType()));
        pd.setTitle("Conflict");
        if (ex.getCurrentVersion() != null) {
            pd.setProperty("currentVersion", ex.getCurrentVersion());
        }
        return pd;
    }

    @ExceptionHandler(AppNotFoundException.class)
    public ProblemDetail handleNotFoundException(AppNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Not Found");
        return pd;
    }

    @ExceptionHandler(AppBadRequestException.class)
    public ProblemDetail handleBadRequestException(AppBadRequestException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Bad Request");
        return pd;
    }

    @ExceptionHandler(MaxPayloadSizeExceededException.class)
    public ProblemDetail handleMaxPayloadSizeExceeded(MaxPayloadSizeExceededException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "Request body exceeds maximum size of 81920 bytes");
        pd.setTitle("Payload Too Large");
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalid JSON syntax");
        pd.setTitle("Bad Request");
        return pd;
    }
}
