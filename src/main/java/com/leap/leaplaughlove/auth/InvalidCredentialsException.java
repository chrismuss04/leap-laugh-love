package com.leap.leaplaughlove.auth;

/** Thrown when email/password do not match a valid, unlocked client. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
