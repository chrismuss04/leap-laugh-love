package com.leap.leaplaughlove.account.position;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for managing trading positions of accounts.
 * Provides endpoints to retrieve positions for a specific account.
 */
@RestController
@RequestMapping("/api/account")
public class PositionController {

    private final PositionService positionService;

    /**
     * Constructs a new PositionController with the injected PositionService.
     * @param positionService the service used to manage positions
     */
    public PositionController(PositionService positionService) {
        this.positionService = positionService;
    }

    /**
     * Retrieves the trading positions for the specified account.
     * @param accountId the unique identifier of the account
     * @return a ResponseEntity containing the positions of the account
     */
    @GetMapping("/accounts/{accountId}/positions")
    public ResponseEntity<PositionsResponse> getPositionsForAccount(@PathVariable UUID accountId) {
        return ResponseEntity.ok(positionService.getPositionsForAuthenticatedClientAccount(accountId));
    }
}

