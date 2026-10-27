package com.leap.leaplaughlove.marketdata.stream;

import com.leap.leaplaughlove.common.security.ClientSessionValidator;
import com.leap.leaplaughlove.marketdata.stream.PriceStreamBroadcaster.StreamSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Closes the price streams whose login session has ended (Session Timeout & Revocation).
 *
 * <p>
 * Every open stream's session is checked in one query per sweep, however many tabs, users or
 * symbols are streaming. Checking each stream as it sent prices cost a query per stream per
 * batch, which grew with users until it exhausted the database's connections, and let a slow
 * database hold up sending prices.
 *
 * <p>
 * If the session store can't be read, every stream is left open until the next sweep: a slow
 * moment in the database is not a logout. Each request a client makes is still checked on its
 * own, so this only delays when an ended session's price stream stops.
 */
@Component
public class PriceStreamSessionSweep {

    private static final Logger log = LoggerFactory.getLogger(PriceStreamSessionSweep.class);

    private final PriceStreamBroadcaster broadcaster;
    private final ClientSessionValidator sessions;
    private final Clock clock;

    /**
     * Creates a sweep over the broadcaster's streams, timed by the UTC system clock.
     *
     * @param broadcaster the streams to sweep
     * @param sessions    checks which sessions are still live
     */
    @Autowired
    public PriceStreamSessionSweep(PriceStreamBroadcaster broadcaster, ClientSessionValidator sessions) {
        this(broadcaster, sessions, Clock.systemUTC());
    }

    PriceStreamSessionSweep(PriceStreamBroadcaster broadcaster, ClientSessionValidator sessions, Clock clock) {
        this.broadcaster = broadcaster;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * Closes every open stream whose token has expired or whose session is no longer live.
     */
    @Scheduled(fixedDelayString = "${marketdata.stream.session-sweep-interval-ms:20000}",
            initialDelayString = "${marketdata.stream.session-sweep-interval-ms:20000}")
    public void sweep() {
        Set<StreamSession> open = broadcaster.openSessions();
        if (open.isEmpty()) {
            return;
        }
        Map<UUID, UUID> live;
        try {
            live = sessions.activeClientSessions(
                    open.stream().map(StreamSession::sessionId).collect(Collectors.toSet()));
        } catch (DataAccessException ex) {
            log.warn("Could not check {} price stream sessions; leaving them open until the next sweep: {}",
                    open.size(), ex.getMessage());
            return;
        }
        Instant now = clock.instant();
        broadcaster.closeSessions(session -> !session.expiresAt().isAfter(now)
                || !session.clientId().equals(live.get(session.sessionId())));
    }
}
