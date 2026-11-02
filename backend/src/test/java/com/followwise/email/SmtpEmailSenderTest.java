package com.followwise.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(OutputCaptureExtension.class)
class SmtpEmailSenderTest {

    @Test
    void buildsAndSendsCorrectMessageWithCapturedLogs(CapturedOutput output) {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        SmtpEmailSender sender = new SmtpEmailSender(
                mailSender,
                "noreply@followwise.com",
                "FollowWise",
                "support@followwise.com"
        );

        String token = "super-secret-one-time-token-99999";
        String body = "Please click https://app.followwise.com/verify-email?token=" + token;

        sender.send("recipient@example.com", "Verify your email", body);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage sentMessage = captor.getValue();
        assertThat(sentMessage.getTo()).containsExactly("recipient@example.com");
        assertThat(sentMessage.getSubject()).isEqualTo("Verify your email");
        assertThat(sentMessage.getText()).isEqualTo(body);
        assertThat(sentMessage.getFrom()).isEqualTo("FollowWise <noreply@followwise.com>");
        assertThat(sentMessage.getReplyTo()).isEqualTo("support@followwise.com");

        // Verify no token or email body appears in logs under SMTP transport
        assertThat(output.getAll()).doesNotContain(token);
        assertThat(output.getAll()).doesNotContain("Please click");
    }
}
