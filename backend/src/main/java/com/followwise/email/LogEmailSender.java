package com.followwise.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

    @Override
    public void send(String to, String subject, String bodyText) {
        log.info("Sending email to {}: subject='{}'\n{}", to, subject, bodyText);
    }
}
