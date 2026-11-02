package com.followwise.identity;

import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class PasswordService {

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final String dummyHash = passwordEncoder.encode("dummy-password-for-timing");

    public String encode(String rawPassword) {
        validatePassword(rawPassword);
        return passwordEncoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    public void runDummyMatch(String rawPassword) {
        passwordEncoder.matches(rawPassword, dummyHash);
    }

    public void validatePassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password must be between 10 and 72 UTF-8 bytes");
        }
        byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 10 || bytes.length > 72) {
            throw new IllegalArgumentException("Password must be between 10 and 72 UTF-8 bytes");
        }
    }
}
