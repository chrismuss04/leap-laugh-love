package com.leap.leaplaughlove.marketdata.stream;

import com.leap.leaplaughlove.marketdata.simulation.PriceState;
import com.leap.leaplaughlove.marketdata.simulation.PriceTickEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Broadcasts live simulation ticks to subscribed clients over Server-Sent Events, optionally
 * filtered to a subset of symbols per subscriber.
 */
@Component
public class PriceStreamBroadcaster {

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    /**
     * Registers a new SSE subscriber, optionally filtered to a set of symbols.
     * @param symbolFilter the symbols to send to this subscriber, or empty to send all
     * @return the emitter the subscriber should be returned to the client
     */
    public SseEmitter subscribe(Set<String> symbolFilter) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription(emitter, symbolFilter);
        subscriptions.add(subscription);
        emitter.onCompletion(() -> subscriptions.remove(subscription));
        emitter.onTimeout(() -> subscriptions.remove(subscription));
        emitter.onError(ex -> subscriptions.remove(subscription));
        return emitter;
    }

    /**
     * Sends a simulation tick to every subscriber whose symbol filter matches it, removing
     * any subscriber whose emitter fails.
     * @param event the simulation tick event to broadcast
     */
    @EventListener
    public void onPriceTick(PriceTickEvent event) {
        PriceState state = event.priceState();
        for (Subscription subscription : subscriptions) {
            if (!subscription.matches(state.symbol())) {
                continue;
            }
            try {
                subscription.emitter().send(SseEmitter.event().name("price").data(state));
            } catch (IOException | IllegalStateException ex) {
                // The client went away (tab closed, or the frontend reconnecting with a new
                // symbol set). Just drop it: the container is already running its own error
                // handling for the request, and completeWithError() from this non-container
                // thread makes Tomcat throw - which would escape into the simulation tick and
                // skip every instrument after this one.
                subscriptions.remove(subscription);
            }
        }
    }

    /**
     * One SSE subscriber and the symbol filter it registered with.
     * @param emitter the subscriber's SSE emitter
     * @param symbolFilter the symbols to send to this subscriber, or empty to send all
     */
    private record Subscription(SseEmitter emitter, Set<String> symbolFilter) {
        /**
         * Checks whether a symbol should be sent to this subscriber.
         * @param symbol the instrument symbol of the tick being broadcast
         * @return true if this subscriber's filter is empty or contains the symbol
         */
        boolean matches(String symbol) {
            return symbolFilter.isEmpty() || symbolFilter.contains(symbol);
        }
    }
}
