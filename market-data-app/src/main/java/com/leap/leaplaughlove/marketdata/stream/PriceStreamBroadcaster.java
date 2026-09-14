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

@Component
public class PriceStreamBroadcaster {

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe(Set<String> symbolFilter) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription(emitter, symbolFilter);
        subscriptions.add(subscription);
        emitter.onCompletion(() -> subscriptions.remove(subscription));
        emitter.onTimeout(() -> subscriptions.remove(subscription));
        emitter.onError(ex -> subscriptions.remove(subscription));
        return emitter;
    }

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
                subscription.emitter().completeWithError(ex);
                subscriptions.remove(subscription);
            }
        }
    }

    private record Subscription(SseEmitter emitter, Set<String> symbolFilter) {
        boolean matches(String symbol) {
            return symbolFilter.isEmpty() || symbolFilter.contains(symbol);
        }
    }
}
