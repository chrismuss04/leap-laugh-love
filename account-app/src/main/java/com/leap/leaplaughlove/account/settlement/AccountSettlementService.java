package com.leap.leaplaughlove.account.settlement;

import com.leap.leaplaughlove.account.account.Account;
import com.leap.leaplaughlove.account.account.AccountAuthorizationService;
import com.leap.leaplaughlove.account.balance.BalanceService;
import com.leap.leaplaughlove.account.ledger.CashLedgerEntry;
import com.leap.leaplaughlove.account.ledger.CashLedgerRepository;
import com.leap.leaplaughlove.account.position.Position;
import com.leap.leaplaughlove.account.position.PositionId;
import com.leap.leaplaughlove.account.position.PositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Service to handle pre-trade validation checks and atomic trade settlements for orders.
 */
@Service
public class AccountSettlementService {

    private final AccountAuthorizationService accountAuthorizationService;
    private final BalanceService balanceService;
    private final CashLedgerRepository cashLedgerRepository;
    private final PositionRepository positionRepository;

    /**
     * Constructs an AccountSettlementService with the injected services and repositories.
     * @param accountAuthorizationService the service for handling account authorization
     * @param balanceService the service for retrieving account balances
     * @param cashLedgerRepository the repository for managing cash ledger entries
     * @param positionRepository the repository for managing account positions
     */
    public AccountSettlementService(AccountAuthorizationService accountAuthorizationService,
                                  BalanceService balanceService,
                                  CashLedgerRepository cashLedgerRepository,
                                  PositionRepository positionRepository) {
        this.accountAuthorizationService = accountAuthorizationService;
        this.balanceService = balanceService;
        this.cashLedgerRepository = cashLedgerRepository;
        this.positionRepository = positionRepository;
    }

    /**
     * Gathers account eligibility, current cash balance, and instrument holdings for pre-trade validation.
     * @param accountId the unique identifier of the account
     * @param instrumentId the unique identifier of the instrument (optional)
     * @return the account validation data encapsulated in an AccountValidationDto
     */
    public AccountValidationDto getValidationData(UUID accountId, UUID instrumentId) {
        Account account = accountAuthorizationService.getAuthorizedAccount(accountId);
        BigDecimal cashBalance = balanceService.getCurrentBalance(account);

        long holdingQuantity = 0L;
        if (instrumentId != null) {
            holdingQuantity = positionRepository.findById(new PositionId(accountId, instrumentId))
                    .map(Position::getQuantity)
                    .orElse(0L);
        }

        return new AccountValidationDto(
                AccountAuthorizationService.ACTIVE_STATUS.equals(account.getStatus()),
                account.isTradingEnabled(),
                cashBalance != null ? cashBalance : BigDecimal.ZERO,
                holdingQuantity,
                account.getBaseCurrency(),
                account.getAccountNumber()
        );
    }

    /**
     * Settles an executed order atomically by posting to the cash ledger and updating holdings.
     * @param accountId the unique identifier of the account
     * @param request the settlement request containing order details
     * @return the settlement response encapsulated in a SettlementResponse DTO
     */
    @Transactional(rollbackFor = Exception.class)
    public SettlementResponse settleOrder(UUID accountId, SettlementRequest request) {
        Account account = accountAuthorizationService.getAuthorizedTradingAccountForUpdate(accountId);
        BigDecimal tradeAmount = request.price().multiply(BigDecimal.valueOf(request.quantity()))
                .setScale(2, RoundingMode.HALF_UP);
        OffsetDateTime time = request.executedAt() != null ? request.executedAt() : OffsetDateTime.now();

        // 1. Post to Cash Ledger
        BigDecimal ledgerAmount;
        String entryType;
        String description;

        boolean isBuy = "BUY".equalsIgnoreCase(request.side());
        if (isBuy) {
            ledgerAmount = tradeAmount.negate();
            entryType = "BUY_SETTLEMENT";
            description = "Buy settlement: " + request.symbol() + " " + request.quantity() + " @ $" + request.price();
        } else {
            ledgerAmount = tradeAmount;
            entryType = "SELL_SETTLEMENT";
            description = "Sell settlement: " + request.symbol() + " " + request.quantity() + " @ $" + request.price();
        }

        CashLedgerEntry ledgerEntry = new CashLedgerEntry(
                account.getAccountId(),
                request.orderId(),
                request.executionId(),
                entryType,
                ledgerAmount,
                account.getBaseCurrency(),
                time,
                description);
        ledgerEntry = cashLedgerRepository.save(ledgerEntry);

        // 2. Update Position (Holdings) with pessimistic write lock
        Optional<Position> existingOpt = positionRepository.findByIdForUpdate(account.getAccountId(), request.instrumentId());

        long newQty;
        BigDecimal newAvgCost;

        if (isBuy) {
            if (existingOpt.isEmpty()) {
                newQty = request.quantity();
                newAvgCost = request.price();
                Position newPosition = new Position(
                        account.getAccountId(), request.instrumentId(), newQty, newAvgCost, time);
                positionRepository.save(newPosition);
            } else {
                Position p = existingOpt.get();
                long oldQty = p.getQuantity();
                BigDecimal oldAvg = p.getAvgCost();
                newQty = oldQty + request.quantity();
                BigDecimal totalCost = oldAvg.multiply(BigDecimal.valueOf(oldQty)).add(tradeAmount);
                newAvgCost = totalCost.divide(BigDecimal.valueOf(newQty), 6, RoundingMode.HALF_UP);

                p.setQuantity(newQty);
                p.setAvgCost(newAvgCost);
                p.setUpdatedAt(time);
                positionRepository.save(p);
            }
        } else {
            Position p = existingOpt.orElseThrow(() ->
                    new IllegalStateException("Cannot settle sell: position does not exist"));
            if (p.getQuantity() < request.quantity()) {
                throw new IllegalStateException("Cannot settle sell: insufficient position quantity");
            }
            newQty = p.getQuantity() - request.quantity();
            newAvgCost = newQty == 0 ? BigDecimal.ZERO : p.getAvgCost();
            p.setQuantity(newQty);
            p.setAvgCost(newAvgCost);
            p.setUpdatedAt(time);
            positionRepository.save(p);
        }

        // 3. Balance after settlement
        BigDecimal balanceAfter = balanceService.getCurrentBalance(account);

        return new SettlementResponse(
                ledgerEntry.getCashLedgerId(),
                balanceAfter,
                newQty,
                newAvgCost);
    }
}

