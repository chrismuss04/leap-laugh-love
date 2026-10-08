package com.leap.leaplaughlove.iam.auth;

import com.leap.leaplaughlove.common.security.Role;

/**
 * Record to represent a login response in the IAM system.
 * @param accessToken the access token issued upon successful login
 * @param tokenType the type of the token (e.g., Bearer)
 * @param expiresInSeconds the duration in seconds for which the token is valid
 * @param role who signed in; the UI sends clients to trading and staff to reporting
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        Role role
) {
    /**
     * A client's login response.
     * @param accessToken the access token issued upon successful login
     * @param tokenType the type of the token (e.g., Bearer)
     * @param expiresInSeconds the duration in seconds for which the token is valid
     */
    public LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {
        this(accessToken, tokenType, expiresInSeconds, Role.CLIENT);
    }
}
