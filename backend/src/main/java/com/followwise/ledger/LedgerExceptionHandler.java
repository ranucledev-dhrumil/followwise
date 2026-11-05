package com.followwise.ledger;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class LedgerExceptionHandler {

    @ExceptionHandler(LedgerValidationException.class)
    public ProblemDetail handleValidationException(LedgerValidationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed");
        pd.setTitle("Unprocessable Entity");
        pd.setProperty("errors", ex.getErrors());
        return pd;
    }

    @ExceptionHandler(LedgerConflictException.class)
    public ProblemDetail handleConflictException(LedgerConflictException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create(ex.getConflictType()));
        pd.setTitle("Conflict");
        return pd;
    }

    @ExceptionHandler(LedgerNotFoundException.class)
    public ProblemDetail handleNotFoundException(LedgerNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Not Found");
        return pd;
    }

    @ExceptionHandler(LedgerBadRequestException.class)
    public ProblemDetail handleBadRequestException(LedgerBadRequestException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Bad Request");
        return pd;
    }
}
