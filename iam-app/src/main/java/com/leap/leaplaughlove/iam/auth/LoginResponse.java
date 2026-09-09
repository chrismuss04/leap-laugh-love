package com.leap.leaplaughlove.iam.auth;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {}
