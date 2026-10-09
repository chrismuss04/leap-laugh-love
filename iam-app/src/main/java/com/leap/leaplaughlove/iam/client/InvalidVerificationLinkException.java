package com.leap.leaplaughlove.iam.client;

/**
 * Thrown when the link from a registration email cannot open an account. The message is the same
 * whether the link is unknown, expired, already used, or the details it was issued for have since
 * been registered by someone else.
 */
public class InvalidVerificationLinkException extends RuntimeException {

    /**
     * Constructor for InvalidVerificationLinkException
     */
    public InvalidVerificationLinkException() {
        super("This link is invalid, has expired or has already been used");
    }
}
