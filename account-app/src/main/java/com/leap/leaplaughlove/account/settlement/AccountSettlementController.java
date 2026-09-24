package com.leap.leaplaughlove.account.settlement;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Controller for handling account settlement-related operations.
 * Provides endpoints for retrieving account validation data and settling orders.
 */
@RestController
@RequestMapping("/api/account/internal/accounts/{accountId}")
public class AccountSettlementController {

    private final AccountSettlementService accountSettlementService;

    /**
     * Constructs an AccountSettlementController with the injected AccountSettlementService.
     * @param accountSettlementService the service for handling account settlement operations
     */
    public AccountSettlementController(AccountSettlementService accountSettlementService) {
        this.accountSettlementService = accountSettlementService;
    }

    /**
     * Retrieves the validation data for a specific account and optionally for a specific instrument.
     * @param accountId the unique identifier of the account
     * @param instrumentId the unique identifier of the instrument (optional)
     * @return the account validation data wrapped in a ResponseEntity
     */
    @GetMapping("/validation-data")
    public ResponseEntity<AccountValidationDto> getValidationData(
            @PathVariable UUID accountId,
            @RequestParam(required = false) UUID instrumentId) {
        return ResponseEntity.ok(accountSettlementService.getValidationData(accountId, instrumentId));
    }
    /**
     * Settles an order for a specific account.
     * @param accountId the unique identifier of the account
     * @param request the settlement request containing order details
     * @return the settlement response wrapped in a ResponseEntity
     */
    @PostMapping("/settlement")
    public ResponseEntity<SettlementResponse> settleOrder(
            @PathVariable UUID accountId,
            @Valid @RequestBody SettlementRequest request) {
        return ResponseEntity.ok(accountSettlementService.settleOrder(accountId, request));
    }
}

