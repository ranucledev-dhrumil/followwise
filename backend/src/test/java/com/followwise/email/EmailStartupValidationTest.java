package com.followwise.email;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EmailStartupValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MailConfig.class);

    @Test
    void startupFailsWhenTransportIsBlankOrInvalid() {
        runner.withPropertyValues("mail.transport=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("MAIL_TRANSPORT must be exactly 'smtp' or 'log'");
                });

        runner.withPropertyValues("mail.transport=invalid")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("MAIL_TRANSPORT must be exactly 'smtp' or 'log'");
                });
    }

    @Test
    void startupFailsWhenLogTransportOutsideLocalOrTestProfile() {
        // No profile active
        runner.withPropertyValues("mail.transport=log")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Log transport is only allowed in 'local' or 'test' profile");
                });

        // Prod profile active
        runner.withPropertyValues("mail.transport=log", "spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Log transport is only allowed in 'local' or 'test' profile");
                });

        // Dev profile active
        runner.withPropertyValues("mail.transport=log", "spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Log transport is only allowed in 'local' or 'test' profile");
                });
    }

    @Test
    void startupSucceedsWhenLogTransportInLocalOrTestProfile() {
        runner.withPropertyValues("mail.transport=log", "spring.profiles.active=test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmailSender.class);
                    assertThat(context.getBean(EmailSender.class)).isInstanceOf(LogEmailSender.class);
                });

        runner.withPropertyValues("mail.transport=log", "spring.profiles.active=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmailSender.class);
                    assertThat(context.getBean(EmailSender.class)).isInstanceOf(LogEmailSender.class);
                });
    }

    @Test
    void startupFailsWhenSmtpSettingsMissing() {
        // Missing host
        runner.withPropertyValues(
                "mail.transport=smtp",
                "mail.username=user@example.com",
                "mail.password=secret"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("MAIL_HOST must not be blank when using SMTP transport");
        });

        // Missing username
        runner.withPropertyValues(
                "mail.transport=smtp",
                "mail.host=smtp.example.com",
                "mail.password=secret"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("MAIL_USERNAME must not be blank when using SMTP transport");
        });

        // Missing password
        runner.withPropertyValues(
                "mail.transport=smtp",
                "mail.host=smtp.example.com",
                "mail.username=user@example.com"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("MAIL_PASSWORD must not be blank when using SMTP transport");
        });
    }

    @Test
    void startupSucceedsWhenSmtpSettingsValid() {
        runner.withPropertyValues(
                "mail.transport=smtp",
                "mail.host=smtp.example.com",
                "mail.username=user@example.com",
                "mail.password=secret"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EmailSender.class);
            assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
        });
    }
}
