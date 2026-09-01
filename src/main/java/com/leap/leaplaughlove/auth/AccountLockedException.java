package com.leap.leaplaughlove.auth;

import java.time.OffsetDateTime;

/** Thrown when a client account is temporarily locked out due to repeated failed sign-ins. */
public class AccountLockedException extends RuntimeException {

    private final OffsetDateTime lockedUntil;

    public AccountLockedException(OffsetDateTime lockedUntil) {
        super("Account is locked until " + lockedUntil);
        this.lockedUntil = lockedUntil;
    }

    public OffsetDateTime getLockedUntil() {
        return lockedUntil;
    }
}
