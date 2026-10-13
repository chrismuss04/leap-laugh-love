package com.leap.leaplaughlove.iam.client;

/**
 * Thrown when a signed-in client tries to change their email address or phone number to one that
 * already belongs to another account. Unlike registration, the client is told which it was: they
 * are signed in, and can't fix the form otherwise.
 */
public class DetailTakenException extends RuntimeException {

    private final String error;

    /**
     * Constructor for DetailTakenException
     * @param error the code naming which detail is taken (EMAIL_TAKEN or PHONE_TAKEN)
     * @param message the message shown to the client
     */
    public DetailTakenException(String error, String message) {
        super(message);
        this.error = error;
    }

    /**
     * Method to retrieve the code naming which detail is taken.
     * @return the error code
     */
    public String getError() {
        return error;
    }
}
