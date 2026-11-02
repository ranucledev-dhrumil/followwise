package com.followwise.authtoken;

public record IssuedToken(String rawToken, AuthToken authToken) {
}
