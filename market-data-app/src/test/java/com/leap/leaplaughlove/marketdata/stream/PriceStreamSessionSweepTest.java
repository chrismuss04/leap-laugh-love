package com.leap.leaplaughlove.marketdata.stream;

import com.leap.leaplaughlove.common.security.ClientSessionValidator;
import com.leap.leaplaughlove.marketdata.stream.PriceStreamBroadcaster.StreamSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("PriceStreamSessionSweep Unit Tests")
class PriceStreamSessionSweepTest {

    private final Instant now = Instant.parse("2026-10-26T12:00:00Z");
    private final PriceStreamBroadcaster broadcaster = new PriceStreamBroadcaster(Runnable::run);
    private final ClientSessionValidator sessions = mock(ClientSessionValidator.class);
    private final PriceStreamSessionSweep sweep =
            new PriceStreamSessionSweep(broadcaster, sessions, Clock.fixed(now, ZoneOffset.UTC));

    private StreamSession open(Instant expiresAt) {
        StreamSession session = new StreamSession(UUID.randomUUID(), UUID.randomUUID(), expiresAt);
        broadcaster.register(new SseEmitter(0L), Set.of(), session);
        return session;
    }

    @Test
    @DisplayName("checks every open stream's session in one lookup and closes the ended ones")
    void closesEndedSessionsInOneLookup() {
        StreamSession live = open(now.plusSeconds(600));
        StreamSession revoked = open(now.plusSeconds(600));
        StreamSession tokenExpired = open(now);
        StreamSession otherClient = open(now.plusSeconds(600));
        when(sessions.activeClientSessions(any())).thenReturn(Map.of(
                live.sessionId(), live.clientId(),
                tokenExpired.sessionId(), tokenExpired.clientId(),
                otherClient.sessionId(), UUID.randomUUID()));

        sweep.sweep();

        verify(sessions).activeClientSessions(Set.of(live.sessionId(), revoked.sessionId(),
                tokenExpired.sessionId(), otherClient.sessionId()));
        assertEquals(Set.of(live), broadcaster.openSessions());
    }

    @Test
    @DisplayName("leaves streams open when the session store can't be read")
    void keepsStreamsOpenWhenTheDatabaseFails() {
        StreamSession session = open(now.plusSeconds(600));
        when(sessions.activeClientSessions(any())).thenThrow(new DataAccessResourceFailureException("offline"));

        sweep.sweep();

        assertEquals(Set.of(session), broadcaster.openSessions());
    }

    @Test
    @DisplayName("doesn't touch the database when nothing is streaming")
    void skipsTheLookupWithNoStreams() {
        sweep.sweep();

        verifyNoInteractions(sessions);
        assertTrue(broadcaster.openSessions().isEmpty());
    }
}
