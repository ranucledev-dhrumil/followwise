package com.followwise.email;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Arrays;
import java.util.Properties;

@Configuration
public class MailConfig {

    private final Environment environment;

    @Value("${mail.transport:}")
    private String transport;

    @Value("${mail.host:}")
    private String host;

    @Value("${mail.port:587}")
    private int port;

    @Value("${mail.username:}")
    private String username;

    @Value("${mail.password:}")
    private String password;

    @Value("${mail.from-address:}")
    private String fromAddress;

    @Value("${mail.from-name:FollowWise}")
    private String fromName;

    @Value("${mail.reply-to:}")
    private String replyTo;

    public MailConfig(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        if (!"smtp".equals(transport) && !"log".equals(transport)) {
            throw new IllegalStateException("MAIL_TRANSPORT must be exactly 'smtp' or 'log'");
        }

        if ("log".equals(transport)) {
            String[] activeProfiles = environment.getActiveProfiles();
            boolean isAllowed = activeProfiles.length > 0 && Arrays.stream(activeProfiles)
                    .anyMatch(p -> "local".equalsIgnoreCase(p) || "test".equalsIgnoreCase(p));
            if (!isAllowed) {
                throw new IllegalStateException("Log transport is only allowed in 'local' or 'test' profile");
            }
        }

        if ("smtp".equals(transport)) {
            if (host == null || host.isBlank()) {
                throw new IllegalStateException("MAIL_HOST must not be blank when using SMTP transport");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalStateException("MAIL_USERNAME must not be blank when using SMTP transport");
            }
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("MAIL_PASSWORD must not be blank when using SMTP transport");
            }
        }
    }

    @Bean
    public EmailSender emailSender() {
        if ("log".equals(transport)) {
            return new LogEmailSender();
        }
        return new SmtpEmailSender(javaMailSender(), fromAddress, fromName, replyTo);
    }

    private JavaMailSender javaMailSender() {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        return sender;
    }
}
