package com.leap.leaplaughlove.marketdata.stream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
public class PriceStreamController {

    private final PriceStreamBroadcaster broadcaster;

    public PriceStreamController(PriceStreamBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

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
