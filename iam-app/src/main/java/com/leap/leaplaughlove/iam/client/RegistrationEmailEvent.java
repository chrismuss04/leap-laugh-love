package com.leap.leaplaughlove.iam.client;

import java.time.Instant;
import java.util.Set;

/**
 * Published when a registration request needs an email sent, so it goes out once the request has
 * committed. Every request emails the address it was submitted with: the response never says how
 * the application went, so the email is where the applicant finds out.
 * @param kind which email to send
 * @param to the address to send it to
 * @param link the link that confirms the email and opens the account; only set for {@link Kind#VERIFY}
 * @param expiresAt when that link stops working; only set for {@link Kind#VERIFY}
 * @param details which of the recipient's details the application used; only set for
 *                {@link Kind#DETAILS_REUSED}. Never the values themselves.
 */
public record RegistrationEmailEvent(Kind kind, String to, String link, Instant expiresAt, Set<Detail> details) {

    /** The emails a registration request can lead to. */
    public enum Kind {
        /** The application was stored: here is the link that opens the account. */
        VERIFY,
        /** The address already has an account, and nothing else of that client's was used. */
        ALREADY_REGISTERED,
        /** The phone number or SSN belongs to another client, so nothing was opened. */
        NOT_COMPLETED,
        /** Tells a client someone applied with their phone number or SSN, listing everything of theirs used. */
        DETAILS_REUSED
    }

    /** The details of an existing client an application can reuse, in the order an email lists them. */
    public enum Detail {
        EMAIL,
        PHONE,
        SSN
    }

    static RegistrationEmailEvent verify(String to, String link, Instant expiresAt) {
        return new RegistrationEmailEvent(Kind.VERIFY, to, link, expiresAt, Set.of());
    }

    static RegistrationEmailEvent of(Kind kind, String to) {
        return new RegistrationEmailEvent(kind, to, null, null, Set.of());
    }

    static RegistrationEmailEvent detailsReused(String to, Set<Detail> details) {
        return new RegistrationEmailEvent(Kind.DETAILS_REUSED, to, null, null, Set.copyOf(details));
    }
}
