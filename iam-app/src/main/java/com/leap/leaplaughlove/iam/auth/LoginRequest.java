package com.leap.leaplaughlove.iam.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Record to represent a login request in the IAM system.
 * Represents the data required for a user to log in.
 * @param email the email address of the user attempting to log in
 * @param password the password of the user attempting to log in
 */
public record LoginRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be valid")
        String email,

        @NotBlank(message = "password is required")
        String password
) {}
