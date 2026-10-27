package com.leap.leaplaughlove.marketdata.stream;

import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.marketdata.stream.PriceStreamBroadcaster.StreamSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PriceStreamController Unit Tests")
class PriceStreamControllerTest {

    private final PriceStreamBroadcaster broadcaster = mock(PriceStreamBroadcaster.class);
    private final PriceStreamController controller = new PriceStreamController(broadcaster);
    private final SseEmitter emitter = new SseEmitter();
    private final UUID clientId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final Instant expiresAt = Instant.now().plusSeconds(600);

    @BeforeEach
    void authenticate() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(clientId.toString(), null, List.of());
        authentication.setDetails(new JwtService.TokenIdentity(clientId, sessionId, expiresAt, "access"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(broadcaster.subscribe(any(), any(StreamSession.class))).thenReturn(emitter);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private Set<String> subscribedFilter() {
        ArgumentCaptor<Set<String>> filter = ArgumentCaptor.forClass(Set.class);
        verify(broadcaster).subscribe(filter.capture(), any(StreamSession.class));
        return filter.getValue();
    }

    @Test
    @DisplayName("subscribes to every symbol when no filter is given")
    void noFilterMeansEverySymbol() {
        assertSame(emitter, controller.stream(null));

        assertTrue(subscribedFilter().isEmpty());
    }

    @Test
    @DisplayName("treats a blank filter as no filter")
    void blankFilterMeansEverySymbol() {
        controller.stream("   ");

        assertTrue(subscribedFilter().isEmpty());
    }

    @Test
    @DisplayName("normalises a comma-separated filter to trimmed upper-case symbols")
    void normalisesFilter() {
        controller.stream(" aapl, msft ,,spx");

        assertEquals(Set.of("AAPL", "MSFT", "SPX"), subscribedFilter());
    }

    @Test
    @DisplayName("opens the stream under the caller's session, so the session sweep can close it")
    void recordsTheCallersSession() {
        controller.stream("AAPL");

        verify(broadcaster).subscribe(any(), eq(new StreamSession(sessionId, clientId, expiresAt)));
    }
}
