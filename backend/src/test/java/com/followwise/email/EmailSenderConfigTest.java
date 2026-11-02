package com.followwise.email;

import com.followwise.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@ActiveProfiles("local")
@TestPropertySource(properties = {
        "mail.transport=log",
        "auth.cookie-secure=false"
})
class EmailSenderConfigTest extends AbstractIntegrationTest {

    @Autowired
    private EmailSender emailSender;

    @Test
    void contextLoadsWithLogTransportAndCanSend() {
        assertThat(emailSender).isInstanceOf(LogEmailSender.class);
        assertDoesNotThrow(() -> emailSender.send("user@example.com", "Test Subject", "Test Body"));
    }
}
