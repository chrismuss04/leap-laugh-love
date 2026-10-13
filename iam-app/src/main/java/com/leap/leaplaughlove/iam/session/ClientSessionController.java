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
                || !(authentication.getDetails() instanceof JwtService.TokenIdentity identity)) {
            throw inactive();
        }
        if (!isUsable(identity, now)) {
            throw inactive();
        }
        return identity;
    }

    // A login token for a live session; purpose-scoped service tokens never qualify.
    private static boolean isUsable(JwtService.TokenIdentity identity, Instant now) {
        return identity.sessionId() != null && identity.purpose() == null && identity.expiresAt().isAfter(now);
    }

    private static ResponseStatusException inactive() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session is no longer active");
    }

    @PostMapping("/activity")
    public ResponseEntity<Void> activity(Authentication authentication) {
        Instant now = Instant.now();
        var identity = identity(authentication, now);
        boolean active = identity.role().isStaff()
                ? staffSessions.recordActivity(identity.sessionId(), identity.clientId(), now)
                : sessions.recordActivity(identity.sessionId(), identity.clientId(), now);
        if (!active) {
            throw inactive();
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
