package com.leap.leaplaughlove.iam.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

@DisplayName("LinkTokens Unit Tests")
class LinkTokensTest {

    @Test
    @DisplayName("new tokens are URL-safe, unpadded and different every time")
    void newToken() {
        String first = LinkTokens.newToken();
        String second = LinkTokens.newToken();

        assertTrue(first.matches("[A-Za-z0-9_-]{43}"), first);
        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("hash is the hex SHA-256 of the token")
    void hash() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", LinkTokens.hash("hello"));
    }

    @Test
    @DisplayName("hash fails loudly if SHA-256 is unavailable")
    void hashWithoutSha256() {
        try (MockedStatic<MessageDigest> digest = mockStatic(MessageDigest.class)) {
            digest.when(() -> MessageDigest.getInstance(anyString()))
                    .thenThrow(new NoSuchAlgorithmException("missing"));

            assertThrows(IllegalStateException.class, () -> LinkTokens.hash("hello"));
        }
    }
}
