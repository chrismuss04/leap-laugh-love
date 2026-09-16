package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.account.Account;
import com.leap.leaplaughlove.trading.ledger.CashLedgerRepository;
import com.leap.leaplaughlove.trading.position.Position;
import com.leap.leaplaughlove.trading.position.PositionId;
import com.leap.leaplaughlove.trading.position.PositionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Service providing trade validation logic for order submission.
 * Validates whole-share market orders against client cash balances (BUY)
 * or current position holdings (SELL), ensuring accounts are not locked for trading.
 */
@Service
public class TradeValidationService {

    private final CashLedgerRepository cashLedgerRepository;
    private final PositionRepository positionRepository;

    public TradeValidationService(CashLedgerRepository cashLedgerRepository,
                                  PositionRepository positionRepository) {
        this.cashLedgerRepository = cashLedgerRepository;
        this.positionRepository = positionRepository;
    }

    /**
     * Validates if a trade can be accepted and executed.
     *
     * @param account the account placing the trade
     * @param instrument the instrument being traded
     * @param side BUY or SELL
     * @param quantity the whole share quantity (> 0)
     * @param price the price per share
     * @return TradeValidationResult (accepted or rejected with reason)
     */
    public TradeValidationResult validateTrade(Account account, Instrument instrument,
                                              Order.Side side, long quantity, BigDecimal price) {
        if (account == null || !"ACTIVE".equals(account.getStatus())) {
            return TradeValidationResult.rejected("Account is not active");
        }

        if (!account.isTradingEnabled()) {
            return TradeValidationResult.rejected("Trading is disabled for this account");
        }

        if (instrument == null || !instrument.isTradable()) {
            return TradeValidationResult.rejected("Instrument is not tradable");
        }

        if (quantity <= 0) {
            return TradeValidationResult.rejected("Quantity must be greater than zero");
        }

        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            return TradeValidationResult.rejected("Price must be greater than zero");
        }

        if (side == Order.Side.BUY) {
            BigDecimal totalCost = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal currentBalance = cashLedgerRepository.sumAmountByAccountIdAndCurrency(
                    account.getAccountId(), account.getBaseCurrency());

            if (currentBalance == null || currentBalance.compareTo(totalCost) < 0) {
                return TradeValidationResult.rejected("Insufficient funds - order rejected");
            }

            return TradeValidationResult.accepted();
        } else if (side == Order.Side.SELL) {
            Optional<Position> positionOpt = positionRepository.findById(
                    new PositionId(account.getAccountId(), instrument.getInstrumentId()));

            long currentQuantity = positionOpt.map(Position::getQuantity).orElse(0L);

            if (currentQuantity < quantity) {
                return TradeValidationResult.rejected("Insufficient position quantity - order rejected");
            }

            return TradeValidationResult.accepted();
        }

        return TradeValidationResult.rejected("Unsupported order side: " + side);
    }
}

