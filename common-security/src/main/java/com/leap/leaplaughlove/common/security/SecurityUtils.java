package com.leap.leaplaughlove.common.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.UUID;

/**
 * Utility class for interacting with the Spring Security context in Leap Laugh
 * Love services.
 */
public final class SecurityUtils {

    /**
     * Private constructor for SecurityUtils
     */
    private SecurityUtils() {
    }

    /**
     * Resolves the authenticated client ID from the current Spring Security
     * context.
     *
     * @return the UUID of the authenticated client
     * @throws ResponseStatusException with 401 UNAUTHORIZED if no valid
     *                                 authenticated principal is found, or 403
     *                                 FORBIDDEN if the caller is staff
     */
    public static UUID getAuthenticatedClientId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "A valid authenticated principal is required");
        }
        // Staff tokens carry a service_id, not a client ID. The route rules already keep staff off
        // client endpoints; this stops one being read as a client if a route is ever missed.
        boolean staff = authentication.getAuthorities().stream().anyMatch(granted ->
                Arrays.stream(Role.values()).anyMatch(role ->
                        role.isStaff() && role.authority().equals(granted.getAuthority())));
        if (staff) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Staff accounts cannot use client endpoints");
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
