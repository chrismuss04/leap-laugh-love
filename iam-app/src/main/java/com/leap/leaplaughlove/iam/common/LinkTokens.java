package com.leap.leaplaughlove.iam.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Creates and hashes the single-use tokens carried by emailed links (password reset, registration
 * verification). Only the hash is ever stored.
 */
public final class LinkTokens {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private LinkTokens() {
    }

    /**
     * Generates a new random, URL-safe token.
     * @return the token
     */
    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Hashes a token for storage. The token is long and random, so a fast unsalted hash is enough
     * to keep the stored value unusable.
     * @param token the raw token
     * @return the hex-encoded SHA-256 of the token
     */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
