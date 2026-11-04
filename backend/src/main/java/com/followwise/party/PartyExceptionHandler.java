package com.followwise.party;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class PartyExceptionHandler {

    @ExceptionHandler(PartyValidationException.class)
    public ProblemDetail handleValidationException(PartyValidationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed");
        pd.setTitle("Unprocessable Entity");
        pd.setProperty("errors", ex.getErrors());
        return pd;
    }

    @ExceptionHandler(PartyConflictException.class)
    public ProblemDetail handleConflictException(PartyConflictException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create(ex.getConflictType()));
        pd.setTitle("Conflict");
        if (ex.getCurrentVersion() != null) {
            pd.setProperty("currentVersion", ex.getCurrentVersion());
        }
        return pd;
    }

    @ExceptionHandler(PartyNotFoundException.class)
    public ProblemDetail handleNotFoundException(PartyNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Not Found");
        return pd;
    }

    @ExceptionHandler(PartyBadRequestException.class)
    public ProblemDetail handleBadRequestException(PartyBadRequestException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Bad Request");
        return pd;
    }
}
