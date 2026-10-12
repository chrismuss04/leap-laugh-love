package com.leap.leaplaughlove.marketdata.stream;

import com.leap.leaplaughlove.common.security.ClientSessionValidator;
import com.leap.leaplaughlove.common.security.JwtService;
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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PriceStreamController Unit Tests")
class PriceStreamControllerTest {

    private final PriceStreamBroadcaster broadcaster = mock(PriceStreamBroadcaster.class);
    private final ClientSessionValidator sessions = mock(ClientSessionValidator.class);
    private final PriceStreamController controller = new PriceStreamController(broadcaster, sessions);
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
        when(broadcaster.subscribe(any(), any(BooleanSupplier.class))).thenReturn(emitter);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private Set<String> subscribedFilter() {
        ArgumentCaptor<Set<String>> filter = ArgumentCaptor.forClass(Set.class);
        verify(broadcaster).subscribe(filter.capture(), any(BooleanSupplier.class));
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
    @DisplayName("keeps the stream open only while the caller's session is active")
    void checksTheCallersSession() {
        controller.stream("AAPL");
        ArgumentCaptor<BooleanSupplier> active = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(broadcaster).subscribe(any(), active.capture());

        when(sessions.isActive(sessionId, clientId, expiresAt)).thenReturn(true, false);
        assertTrue(active.getValue().getAsBoolean());
        assertFalse(active.getValue().getAsBoolean());
    }
}
