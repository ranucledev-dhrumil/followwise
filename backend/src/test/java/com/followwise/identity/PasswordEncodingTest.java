package com.followwise.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordEncodingTest {

    private PasswordService passwordService;

    @BeforeEach
    void setUp() {
        passwordService = new PasswordService();
    }

    @Test
    void encodesAndMatchesSuccessfully() {
        String raw = "correct-horse-battery";
        String encoded = passwordService.encode(raw);

        assertThat(encoded).startsWith("{bcrypt}");
        assertThat(passwordService.matches(raw, encoded)).isTrue();
        assertThat(passwordService.matches("wrong-password", encoded)).isFalse();
    }

    @Test
    void passwordLengthValidationAtBoundaries() {
        // 9 bytes: must fail
        String p9 = "123456789";
        assertThat(p9.getBytes(StandardCharsets.UTF_8)).hasSize(9);
        assertThatThrownBy(() -> passwordService.validatePassword(p9))
                .isInstanceOf(IllegalArgumentException.class);

        // 10 bytes: must pass
        String p10 = "1234567890";
        assertThat(p10.getBytes(StandardCharsets.UTF_8)).hasSize(10);
        passwordService.validatePassword(p10);

        // 72 bytes: must pass
        String p72 = "a".repeat(72);
        assertThat(p72.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        passwordService.validatePassword(p72);

        // 73 bytes: must fail
        String p73 = "a".repeat(73);
        assertThat(p73.getBytes(StandardCharsets.UTF_8)).hasSize(73);
        assertThatThrownBy(() -> passwordService.validatePassword(p73))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
