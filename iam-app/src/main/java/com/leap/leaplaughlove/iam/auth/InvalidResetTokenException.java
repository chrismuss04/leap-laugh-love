package com.leap.leaplaughlove.iam.auth;

/**
 * Exception thrown when a password reset token cannot be used.
 * Unknown, expired and already used tokens all raise it with the same message, so the response
 * does not reveal which one it was.
 */
public class InvalidResetTokenException extends RuntimeException {
    /**
     * Constructs a new InvalidResetTokenException with a default error message.
     */
    public InvalidResetTokenException() {
        super("This password reset link is invalid or has expired. Please request a new one.");
    }
}
