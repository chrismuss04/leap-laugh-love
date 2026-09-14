package com.leap.leaplaughlove.iam.auth;

/**
 * Exception thrown when an account is locked.
 */
public class AccountLockedException extends RuntimeException {
    /**
     * Constructs a new AccountLockedException with the specified detail message.
     * @param message the detail message for the exception
     */
    public AccountLockedException(String message) {
        super(message);
    }
}
