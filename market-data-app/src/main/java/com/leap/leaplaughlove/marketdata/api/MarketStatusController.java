package com.leap.leaplaughlove.marketdata.api;

import com.leap.leaplaughlove.common.market.MarketClock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells clients which market session is in force - pre-market, regular, after-hours or closed -
 * when it next changes, and today's trading hours.
 */
@RestController
@RequestMapping("/api/marketdata/market")
public class MarketStatusController {

    private final MarketClock marketClock;

    /**
     * Constructs a MarketStatusController.
     * @param marketClock decides the session
     */
    public MarketStatusController(MarketClock marketClock) {
        this.marketClock = marketClock;
    }

    /**
     * Returns the market's state now.
     * @return the session, what comes next and today's hours
     */
    @GetMapping("/status")
    public MarketStatusResponse status() {
        return MarketStatusResponse.of(marketClock.status());
    }
}
