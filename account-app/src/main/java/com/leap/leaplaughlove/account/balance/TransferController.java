package com.leap.leaplaughlove.account.balance;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for moving cash between the authenticated client's accounts.
 */
@RestController
@RequestMapping("/api/account/balance/transfers")
public class TransferController {

    private final TransferService transferService;

    /**
     * Constructs a TransferController.
     * @param transferService the service that books transfers
     */
    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * Transfers cash between two accounts owned by the authenticated client.
     * @param request the transfer request
     * @return the transfer result including both accounts' balances afterwards
     */
    @PostMapping
    public ResponseEntity<CashTransferResponse> transfer(@Valid @RequestBody CashTransferRequest request) {
        return ResponseEntity.ok(transferService.transfer(request));
    }
}
