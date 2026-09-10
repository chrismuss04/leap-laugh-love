package com.leap.leaplaughlove.iam.auth;

/**
 * Exception thrown when an account is locked.
 */
public class AccountLockedException extends RuntimeException {
    public AccountLockedException(String message) {
        super(message);
    }
}
