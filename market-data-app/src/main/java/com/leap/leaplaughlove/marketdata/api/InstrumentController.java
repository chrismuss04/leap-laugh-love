package com.leap.leaplaughlove.marketdata.api;

import com.leap.leaplaughlove.marketdata.instrument.InstrumentOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Client overview; existing MarketDataSecurityConfig enforces client access. */
@RestController
@RequestMapping("/api/marketdata/instruments")
public class InstrumentController {
    private final InstrumentOverviewService service;

    /**
     * Constructs a new InstrumentController.
     * @param service builds the overview
     */
    public InstrumentController(InstrumentOverviewService service) {
        this.service = service;
    }

    /**
     * Lists every active instrument with its latest price and its change over a period.
     * @param period {@code today}, {@code week} or {@code month}
     * @return the overview, with the change computed against the period's baseline
     */
    @GetMapping
    public InstrumentOverviewResponse getOverview(@RequestParam(defaultValue = "today") String period) {
        return service.getOverview(period);
    }
}
