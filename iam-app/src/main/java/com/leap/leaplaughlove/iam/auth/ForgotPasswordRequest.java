package com.leap.leaplaughlove.iam.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Record to represent a request for a password reset link.
 * @param email the email address of the account whose password was forgotten
 */
public record ForgotPasswordRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be valid")
        String email
) {}
