package com.leap.leaplaughlove.account.balance;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountAuthorizationService;
import com.leap.leaplaughlove.account.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.account.ledger.CashLedgerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Moves cash between two accounts owned by the authenticated client. A transfer is booked as a
 * WITHDRAWAL on the source account and a DEPOSIT on the destination in a single transaction.
 */
@Service
public class TransferService {

    private static final String ENTRY_TYPE_DEPOSIT = "DEPOSIT";
    private static final String ENTRY_TYPE_WITHDRAWAL = "WITHDRAWAL";

    private final AccountAuthorizationService accountAuthorizationService;
    private final CashLedgerRepository cashLedgerRepository;
    private final BalanceService balanceService;

    /**
     * Constructs a TransferService.
     * @param accountAuthorizationService resolves, locks and authorizes the two accounts
     * @param cashLedgerRepository the repository the two ledger legs are written to
     * @param balanceService used to read each account's current balance
     */
    public TransferService(AccountAuthorizationService accountAuthorizationService,
            CashLedgerRepository cashLedgerRepository,
            BalanceService balanceService) {
        this.accountAuthorizationService = accountAuthorizationService;
        this.cashLedgerRepository = cashLedgerRepository;
        this.balanceService = balanceService;
    }

    /**
     * Transfers cash between two of the authenticated client's accounts.
     * @param request the transfer request
     * @return a response carrying both accounts' balances after the transfer
     * @throws ResponseStatusException 400 for an invalid amount, identical accounts, inactive
     *         accounts, mismatched currencies or insufficient funds; 404 for an unowned account
     */
    @Transactional
    public CashTransferResponse transfer(CashTransferRequest request) {
        BigDecimal amount = BalanceService.normalizeAmount(request.amount());

        UUID fromId = request.fromAccountId();
        UUID toId = request.toAccountId();
        if (fromId.equals(toId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot transfer to the same account");
        }

        // Always lock in a fixed order so two opposing transfers cannot deadlock.
        Account from;
        Account to;
        if (fromId.compareTo(toId) < 0) {
            from = accountAuthorizationService.getAuthorizedAccountForUpdate(fromId);
            to = accountAuthorizationService.getAuthorizedAccountForUpdate(toId);
        } else {
            to = accountAuthorizationService.getAuthorizedAccountForUpdate(toId);
            from = accountAuthorizationService.getAuthorizedAccountForUpdate(fromId);
        }

        if (!from.getBaseCurrency().equals(to.getBaseCurrency())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Accounts must share the same currency");
        }

        BigDecimal fromBalance = balanceService.getCurrentBalance(from);
        BigDecimal toBalance = balanceService.getCurrentBalance(to);
        if (amount.compareTo(fromBalance) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Transfer amount cannot exceed available account balance");
        }

        OffsetDateTime now = OffsetDateTime.now();
        CashLedgerEntry withdrawal = cashLedgerRepository.save(new CashLedgerEntry(
                from.getAccountId(),
                ENTRY_TYPE_WITHDRAWAL,
                amount.negate(),
                from.getBaseCurrency(),
                now,
                describe("Transfer to " + to.getAccountNumber(), request.description())));
        cashLedgerRepository.save(new CashLedgerEntry(
                to.getAccountId(),
                ENTRY_TYPE_DEPOSIT,
                amount,
                to.getBaseCurrency(),
                now,
                describe("Transfer from " + from.getAccountNumber(), request.description())));

        return new CashTransferResponse(
                withdrawal.getCashLedgerId(),
                from.getAccountId(),
                to.getAccountId(),
                amount,
                from.getBaseCurrency(),
                fromBalance.subtract(amount),
                toBalance.add(amount),
                now);
    }

    private String describe(String base, String note) {
        return note == null || note.isBlank() ? base : base + " - " + note.trim();
    }
}
