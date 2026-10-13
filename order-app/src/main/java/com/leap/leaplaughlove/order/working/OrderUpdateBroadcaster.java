package com.leap.leaplaughlove.order.working;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Pushes {@link OrderUpdate}s to the clients who have the order stream open, so a limit or stop
 * order that fills in the background shows up on their screen at once rather than at the next poll.
 *
 * <p>Each connection belongs to one client and only ever receives that client's orders. A heartbeat
 * every {@value #HEARTBEAT_SECONDS} seconds finds connections whose client has gone, and closes
 * ones whose login has ended (without extending its activity), as the price stream does. Sends run
 * on virtual threads, so a slow client can't hold up the trigger engine.
 */
@Component
public class OrderUpdateBroadcaster {

    static final int HEARTBEAT_SECONDS = 15;

    private static final Logger log = LoggerFactory.getLogger(OrderUpdateBroadcaster.class);

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Executor sender;

    /** Constructs a broadcaster that sends on virtual threads. */
    public OrderUpdateBroadcaster() {
        this(Executors.newVirtualThreadPerTaskExecutor());
    }

    OrderUpdateBroadcaster(Executor sender) {
        this.sender = sender;
    }

    /**
     * Opens a stream of one client's order updates.
     * @param clientId the client
     * @param sessionActive whether the client's login is still live, checked on every heartbeat
     * @return the emitter streaming to the caller
     */
    public SseEmitter subscribe(UUID clientId, BooleanSupplier sessionActive) {
        return register(new SseEmitter(0L), clientId, sessionActive);
    }

    SseEmitter register(SseEmitter emitter, UUID clientId, BooleanSupplier sessionActive) {
        Subscription subscription = new Subscription(emitter, clientId, sessionActive);
        subscriptions.add(subscription);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(ex -> subscription.close());
        // Tells the browser the stream is up, so it can stop polling as hard.
        sender.execute(() -> subscription.send("ready", "{}"));
        return emitter;
    }

    /**
     * Sends an update to the client's open streams, if they have any.
     * @param clientId the order's owner
     * @param update what happened
     */
    public void publish(UUID clientId, OrderUpdate update) {
        for (Subscription subscription : subscriptions) {
            if (subscription.clientId.equals(clientId)) {
                sender.execute(() -> subscription.send("order", update));
            }
        }
    }

    /**
     * Sends a heartbeat on every stream, which drops those whose client has gone and ends those
     * whose login has expired.
     */
    @Scheduled(fixedDelay = HEARTBEAT_SECONDS * 1000L, initialDelay = HEARTBEAT_SECONDS * 1000L)
    public void heartbeat() {
        for (Subscription subscription : subscriptions) {
            sender.execute(subscription::beat);
        }
    }

    /**
     * Returns how many streams are open.
     * @return the number of open connections
     */
    int openStreams() {
        return subscriptions.size();
    }

    private final class Subscription {
        private final SseEmitter emitter;
        private final UUID clientId;
        private final BooleanSupplier sessionActive;
        private volatile boolean closed;

        private Subscription(SseEmitter emitter, UUID clientId, BooleanSupplier sessionActive) {
            this.emitter = emitter;
            this.clientId = clientId;
            this.sessionActive = sessionActive;
        }

        void send(String event, Object data) {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | RuntimeException ex) {
                // The client went away. The container handles the request's own error path;
                // completing the emitter here can touch a destroyed async context.
                close();
            }
        }

        void beat() {
            if (closed) {
                return;
            }
            boolean active;
            try {
                active = sessionActive.getAsBoolean();
            } catch (RuntimeException ex) {
                // A session store failure must close an otherwise healthy connection too.
                active = false;
            }
            if (!active) {
                close();
                try {
                    emitter.complete();
                } catch (RuntimeException ex) {
                    log.debug("Order stream for client {} already closed: {}", clientId, ex.getMessage());
                }
                return;
            }
            send("ping", "{}");
        }

        void close() {
            closed = true;
            subscriptions.remove(this);
        }
    }
}
