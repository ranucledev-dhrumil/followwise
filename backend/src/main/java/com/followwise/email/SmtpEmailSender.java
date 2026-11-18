package com.followwise.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.regex.Pattern;

public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

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
        send(to, subject, bodyText, null);
    }

    @Override
    public void send(String to, String subject, String bodyText, String customReplyTo) {
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

        String effectiveReplyTo = resolveReplyTo(customReplyTo);
        if (effectiveReplyTo != null && !effectiveReplyTo.isBlank()) {
            message.setReplyTo(effectiveReplyTo);
        }
        mailSender.send(message);
    }

    private String resolveReplyTo(String customReplyTo) {
        if (customReplyTo != null && !customReplyTo.isBlank()) {
            String trimmed = customReplyTo.trim();
            if (!trimmed.contains("\r") && !trimmed.contains("\n") && trimmed.length() <= 254 && EMAIL_PATTERN.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return replyTo;
    }
}
