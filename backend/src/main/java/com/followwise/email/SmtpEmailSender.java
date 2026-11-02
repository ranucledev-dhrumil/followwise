package com.followwise.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;
    private final String replyTo;

    public SmtpEmailSender(JavaMailSender mailSender, String fromAddress, String fromName, String replyTo) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.replyTo = replyTo;
    }

    @Override
    public void send(String to, String subject, String bodyText) {
        log.info("Sending email via SMTP to recipient");
        SimpleMailMessage message = new SimpleMailMessage();
        if (fromName != null && !fromName.isBlank() && fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromName + " <" + fromAddress + ">");
        } else if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setTo(to);
        message.setSubject(subject);
        message.setText(bodyText);
        if (replyTo != null && !replyTo.isBlank()) {
            message.setReplyTo(replyTo);
        }
        mailSender.send(message);
    }
}
