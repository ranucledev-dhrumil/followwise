package com.followwise.email;

public interface EmailSender {
    void send(String to, String subject, String bodyText);

    default void send(String to, String subject, String bodyText, String replyTo) {
        send(to, subject, bodyText);
    }
}
