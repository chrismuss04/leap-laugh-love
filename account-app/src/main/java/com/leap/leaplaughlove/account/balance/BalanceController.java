package com.leap.leaplaughlove.account.balance;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Controller for handling balance-related operations.
 * Provides endpoints for retrieving account balances and performing cash transactions such as deposits and withdrawals.
 */
@RestController
@RequestMapping("/api/account/balance")
public class BalanceController {

    private final BalanceService balanceService;

    /**
     * Constructs a new BalanceController with the injected BalanceService.
     * @param balanceService the BalanceService to be used by this controller
     */
    public BalanceController(BalanceService balanceService) {
        this.balanceService = balanceService;
    }

    /**
     * Retrieves the balance for the currently authenticated client.
     * @return a ResponseEntity containing the balance information for the client
     */
    @GetMapping
    public ResponseEntity<BalanceResponse> getBalance() {
        return ResponseEntity.ok(balanceService.getBalanceForClient());
    }

    /**
     * Performs deposit into the specified account.
     * @param accountId the unique identifier of the account
     * @param request the cash movement request containing deposit details
     * @return a ResponseEntity containing the result of the deposit transaction
     * @throws IllegalArgumentException if the deposit request is invalid
     */
    @PostMapping("/accounts/{accountId}/deposit")
    public ResponseEntity<CashTransactionResponse> deposit(
            @PathVariable UUID accountId,
            @Valid @RequestBody CashMovementRequest request) {
        return ResponseEntity.ok(balanceService.deposit(accountId, request));
    }

    /**
     * Performs withdrawal from the specified account.
     * @param accountId the unique identifier of the account
     * @param request the cash movement request containing withdrawal details
     * @return a ResponseEntity containing the result of the withdrawal transaction
     * @throws IllegalArgumentException if the withdrawal request is invalid
     */
    @PostMapping("/accounts/{accountId}/withdrawal")
    public ResponseEntity<CashTransactionResponse> withdraw(
            @PathVariable UUID accountId,
            @Valid @RequestBody CashMovementRequest request) {
        return ResponseEntity.ok(balanceService.withdraw(accountId, request));
    }
}

