package com.followwise.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecretValidationTest {

    private static final String VALID_SECRET = "12345678901234567890123456789012";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtProperties.class);

    @Test
    void startupFailsWhenSecretIsMissing() {
        runner.withPropertyValues("jwt.secret=", "auth.cookie-secure=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("JWT_SECRET must be at least 32 UTF-8 bytes");
                });
    }

    @Test
    void startupFailsWhenSecretIs31Bytes() {
        String secret31 = "1234567890123456789012345678901"; // 31 chars = 31 UTF-8 bytes
        runner.withPropertyValues("jwt.secret=" + secret31, "auth.cookie-secure=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("JWT_SECRET must be at least 32 UTF-8 bytes");
                });
    }

    @Test
    void startupSucceedsWhenSecretAndCookieSecureAreValid() {
        runner.withPropertyValues("jwt.secret=" + VALID_SECRET, "auth.cookie-secure=true")
                .run(context -> assertThat(context).hasNotFailed());

        runner.withPropertyValues("jwt.secret=" + VALID_SECRET, "auth.cookie-secure=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startupFailsWhenCookieSecureIsInvalidOrBlank() {
        runner.withPropertyValues("jwt.secret=" + VALID_SECRET, "auth.cookie-secure=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("AUTH_COOKIE_SECURE must be exactly 'true' or 'false'");
                });

        runner.withPropertyValues("jwt.secret=" + VALID_SECRET, "auth.cookie-secure=yes")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("AUTH_COOKIE_SECURE must be exactly 'true' or 'false'");
                });
    }
}
