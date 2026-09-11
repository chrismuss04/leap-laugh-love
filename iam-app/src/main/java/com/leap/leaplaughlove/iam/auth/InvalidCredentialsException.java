package com.leap.leaplaughlove.iam.auth;

/**
 * Exception thrown when the provided credentials are invalid.
 * This exception is typically thrown during authentication when the email or password provided by the user is incorrect.
 */
public class InvalidCredentialsException extends RuntimeException {
    /**
     * Constructs a new InvalidCredentialsException with a default error message.
     */
    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
