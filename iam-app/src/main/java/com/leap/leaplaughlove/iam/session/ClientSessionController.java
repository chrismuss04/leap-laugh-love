// Session Timeout & Revocation: both routes require the current authenticated session.
package com.leap.leaplaughlove.iam.session;

import com.leap.leaplaughlove.common.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

@RestController
@RequestMapping("/api/iam/session")
public class ClientSessionController {
    private final ClientSessionRepository sessions;
    // Staff roles: staff sessions live in their own table; the token's role picks which.
    private final StaffSessionRepository staffSessions;

    public ClientSessionController(ClientSessionRepository sessions, StaffSessionRepository staffSessions) {
        this.sessions = sessions;
        this.staffSessions = staffSessions;
    }

    // Take identity from the verified token, never a client-supplied session ID or activity timestamp.
    private JwtService.TokenIdentity identity(Authentication authentication, Instant now) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof JwtService.TokenIdentity identity)
                || identity.sessionId() == null || identity.purpose() != null
                || !identity.expiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session is no longer active");
        }
        return identity;
    }

    @PostMapping("/activity")
    public ResponseEntity<Void> activity(Authentication authentication) {
        Instant now = Instant.now();
        var identity = identity(authentication, now);
        boolean active = identity.role().isStaff()
                ? staffSessions.recordActivity(identity.sessionId(), identity.clientId(), now)
                : sessions.recordActivity(identity.sessionId(), identity.clientId(), now);
        if (!active) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session is no longer active");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication authentication) {
        Instant now = Instant.now();
        var identity = identity(authentication, now);
        if (identity.role().isStaff()) {
            staffSessions.revoke(identity.sessionId(), identity.clientId(), now);
        } else {
            sessions.revoke(identity.sessionId(), identity.clientId(), now);
        }
        return ResponseEntity.noContent().build();
    }
}
