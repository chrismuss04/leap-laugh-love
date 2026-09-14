package com.leap.leaplaughlove.marketdata.stream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exposes the live simulation tick stream as a Server-Sent Events endpoint.
 */
@RestController
public class PriceStreamController {

    private final PriceStreamBroadcaster broadcaster;

    public PriceStreamController(PriceStreamBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * Subscribes the caller to the live tick stream, optionally filtered to a comma-separated
     * list of symbols.
     * @param symbols a comma-separated list of symbols to filter to, or null/blank for all
     * @return the SSE emitter streaming ticks to the caller
     */
    @GetMapping("/api/marketdata/stream")
    public SseEmitter stream(@RequestParam(required = false) String symbols) {
        Set<String> symbolFilter = symbols == null || symbols.isBlank()
                ? Set.of()
                : Arrays.stream(symbols.split(","))
                        .map(String::trim)
                        .map(String::toUpperCase)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
        return broadcaster.subscribe(symbolFilter);
    }
}
