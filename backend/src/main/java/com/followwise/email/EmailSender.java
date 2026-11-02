package com.followwise.email;

public interface EmailSender {
    void send(String to, String subject, String bodyText);
}
