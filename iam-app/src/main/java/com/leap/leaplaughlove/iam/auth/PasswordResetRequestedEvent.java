package com.leap.leaplaughlove.iam.auth;

import java.time.Instant;

/**
 * Published when a reset token is stored, so the link can be delivered once the token has committed.
 * @param email the address to deliver the link to
 * @param resetLink the frontend link carrying the token; this is the only place the token exists
 * @param expiresAt when the link stops working
 */
public record PasswordResetRequestedEvent(String email, String resetLink, Instant expiresAt) {}
