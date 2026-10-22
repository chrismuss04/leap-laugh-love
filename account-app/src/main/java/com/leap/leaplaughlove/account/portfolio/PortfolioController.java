package com.leap.leaplaughlove.account.portfolio;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Controller for whole-portfolio views of the authenticated client's accounts.
 * Maps to the /api/account/portfolio endpoint.
 */
@RestController
@RequestMapping("/api/account/portfolio")
public class PortfolioController {

    private final PortfolioHistoryService portfolioHistoryService;

    /**
     * Constructs a new PortfolioController.
     * @param portfolioHistoryService the service that values the portfolio over time
     */
    public PortfolioController(PortfolioHistoryService portfolioHistoryService) {
        this.portfolioHistoryService = portfolioHistoryService;
    }

    /**
     * Retrieves the value of the authenticated client's accounts over a range, for charting.
     * @param range one of 1D, 1W, 1M, 3M, 1Y or ALL (case-insensitive); defaults to 1M
     * @param accountId one of the client's active accounts to chart on its own; omit for all of them
     * @return the value at each point of the range
     */
    @GetMapping("/history")
    public PortfolioHistoryResponse getHistory(@RequestParam(defaultValue = "1M") String range,
            @RequestParam(required = false) UUID accountId) {
        return portfolioHistoryService.getHistory(PortfolioRange.fromCode(range), accountId);
    }
}

