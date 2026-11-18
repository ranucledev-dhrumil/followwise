package com.followwise.reminder;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class ReminderExceptionHandler {

    @ExceptionHandler(ReminderBadRequestException.class)
    public ProblemDetail handleBadRequestException(ReminderBadRequestException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Bad Request");
        return pd;
    }

    @ExceptionHandler(ReminderNotFoundException.class)
    public ProblemDetail handleNotFoundException(ReminderNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Not Found");
        return pd;
    }

    @ExceptionHandler(ReminderConflictException.class)
    public ProblemDetail handleConflictException(ReminderConflictException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        if (ex.getError() != null) {
            pd.setType(URI.create(ex.getError()));
        }
        pd.setTitle("Conflict");
        if (ex.getCurrentVersion() != null) {
            pd.setProperty("currentVersion", ex.getCurrentVersion());
        }
        return pd;
    }
}
