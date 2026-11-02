package com.followwise.identity;

public record ResetPasswordRequest(String token, String newPassword) {
}
