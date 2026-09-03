package com.leap.leaplaughlove.balance;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/balance")
public class BalanceController {

    private final BalanceService balanceService;

    public BalanceController(BalanceService balanceService) {
        this.balanceService = balanceService;
    }

    @GetMapping
    public ResponseEntity<BalanceResponse> getBalance() {
        return ResponseEntity.ok(balanceService.getBalanceForClient());
    }

    @PostMapping("/accounts/{accountId}/deposit")
    public ResponseEntity<CashTransactionResponse> deposit(
            @PathVariable UUID accountId,
            @Valid @RequestBody CashMovementRequest request) {
        return ResponseEntity.ok(balanceService.deposit(accountId, request));
    }

    @PostMapping("/accounts/{accountId}/withdrawal")
    public ResponseEntity<CashTransactionResponse> withdraw(
            @PathVariable UUID accountId,
            @Valid @RequestBody CashMovementRequest request) {
        return ResponseEntity.ok(balanceService.withdraw(accountId, request));
    }
}
