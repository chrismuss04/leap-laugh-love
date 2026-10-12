package com.leap.leaplaughlove.common.security;

/**
 * Who a session token belongs to, carried in its {@code role} claim and granted to the request as
 * {@code ROLE_<name>}. Clients use the trading app; the two staff roles each have their own
 * dashboard and no access to the trading APIs or to each other's data.
 */
public enum Role {
    /** A customer, signed in with iam.client_credentials; sessions in iam.client_sessions. */
    CLIENT,
    /** Staff who audit trades and reconstruct order lifecycles; sessions in iam.staff_sessions. */
    TRADING_OPERATIONS,
    /** Staff who analyse trading activity, trends and clients; sessions in iam.staff_sessions. */
    COMMERCIAL_ANALYST;

    /**
     * The Spring Security authority this role grants.
     * @return ROLE_ followed by the role's name
     */
    public String authority() {
        return "ROLE_" + name();
    }

    /**
     * Whether this is one of the staff roles, whose sessions live in iam.staff_sessions.
     * @return true for TRADING_OPERATIONS and COMMERCIAL_ANALYST
     */
    public boolean isStaff() {
        return this != CLIENT;
    }
}
