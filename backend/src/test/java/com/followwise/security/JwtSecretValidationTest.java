package com.followwise.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSecretValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtProperties.class);

    @Test
    void startupFailsWhenSecretIsMissing() {
        runner.withPropertyValues("jwt.secret=")
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
        runner.withPropertyValues("jwt.secret=" + secret31)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("JWT_SECRET must be at least 32 UTF-8 bytes");
                });
    }

    @Test
    void startupSucceedsWhenSecretIsAtLeast32Bytes() {
        String secret32 = "12345678901234567890123456789012"; // 32 chars = 32 UTF-8 bytes
        runner.withPropertyValues("jwt.secret=" + secret32)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
