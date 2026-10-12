package com.leap.leaplaughlove.iam.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Record to represent a request to check whether a reset link can still be used.
 * @param token the token from the reset link
 */
public record ResetTokenRequest(
        @NotBlank(message = "token is required")
        String token
) {}
