package com.leap.leaplaughlove.iam.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Record to represent a request to set a new password from an emailed reset link.
 * @param token the token from the reset link
 * @param newPassword the password to set (must be at least 8 characters, as at registration)
 */
public record ResetPasswordRequest(
        @NotBlank(message = "token is required")
        String token,

        @NotBlank(message = "password is required")
        @Size(min = 8, message = "password must be at least 8 characters")
        String newPassword
) {}
