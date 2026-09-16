package com.leap.leaplaughlove.common.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Utility class for interacting with the Spring Security context in Leap Laugh Love services.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * Resolves the authenticated client ID from the current Spring Security context.
     *
     * @return the UUID of the authenticated client
     * @throws ResponseStatusException with 401 UNAUTHORIZED if no valid authenticated principal is found
     */
    public static UUID getAuthenticatedClientId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "A valid authenticated principal is required");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID clientId) {
            return clientId;
        }
        if (principal instanceof String principalString && !principalString.isBlank()
                && !"anonymousUser".equals(principalString)) {
            try {
                return UUID.fromString(principalString);
            } catch (IllegalArgumentException ignored) {
                // fall through to unauthorized
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Authenticated principal is invalid for this operation");
    }
}
