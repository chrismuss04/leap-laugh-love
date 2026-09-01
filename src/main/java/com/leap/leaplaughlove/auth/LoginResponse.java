package com.leap.leaplaughlove.auth;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {
}
