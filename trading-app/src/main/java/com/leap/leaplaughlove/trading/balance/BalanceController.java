package com.leap.leaplaughlove.trading.balance;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/trading/balance")
public class BalanceController {

    private final BalanceService balanceService;

    public BalanceController(BalanceService balanceService) {
        this.balanceService = balanceService;
    }

    /**
     * Retrieves the balance for the currently authenticated client.
     * @return the balance response containing the client's current balance
     */
    @GetMapping
    public ResponseEntity<BalanceResponse> getBalance() {
        return ResponseEntity.ok(balanceService.getBalanceForClient());
    }

    /**
     * Performs the deposit operation for the specified account.
     * @param accountId the ID of the account to deposit into
     * @param request the cash movement request containing the deposit details
     * @return the response containing the result of the deposit operation
     */
    @PostMapping("/accounts/{accountId}/deposit")
    public ResponseEntity<CashTransactionResponse> deposit(
            @PathVariable UUID accountId,
            @Valid @RequestBody CashMovementRequest request) {
        return ResponseEntity.ok(balanceService.deposit(accountId, request));
    }

    /**
     * Performs the withdrawal operation for the specified account.
     * @param accountId the ID of the account to withdraw from
     * @param request the cash movement request containing the withdrawal details
     * @return the response containing the result of the withdrawal operation
     */
    @PostMapping("/accounts/{accountId}/withdrawal")
    public ResponseEntity<CashTransactionResponse> withdraw(
            @PathVariable UUID accountId,
            @Valid @RequestBody CashMovementRequest request) {
        return ResponseEntity.ok(balanceService.withdraw(accountId, request));
    }
}
