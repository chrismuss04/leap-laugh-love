package com.leap.leaplaughlove.iam.auth;

/**
 * Record to represent a login response in the IAM system.
 * @param accessToken the access token issued upon successful login
 * @param tokenType the type of the token (e.g., Bearer)
 * @param expiresInSeconds the duration in seconds for which the token is valid
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {}
