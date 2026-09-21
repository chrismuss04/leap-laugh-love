package com.leap.leaplaughlove.order.validation;

import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.order.Order;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Service providing trade validation logic for order submission.
 * Validates whole-share market orders against client cash balances (BUY)
 * or current position holdings (SELL), ensuring accounts are not locked for trading.
 */
@Service
public class TradeValidationService {

    /**
     * Validates if a trade can be accepted and executed.
     *
     * @param accountValidation the account validation data from Account service
     * @param instrument the instrument being traded
     * @param side BUY or SELL
     * @param quantity the whole share quantity (> 0)
     * @param price the price per share
     * @return TradeValidationResult (accepted or rejected with reason)
     */
    public TradeValidationResult validateTrade(AccountValidationDto accountValidation, Instrument instrument,
                                              Order.Side side, long quantity, BigDecimal price) {
        if (accountValidation == null || !accountValidation.accountActive()) {
            return TradeValidationResult.rejected("Account is not active");
        }

        if (!accountValidation.tradingEnabled()) {
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
            BigDecimal currentBalance = accountValidation.cashBalance();

            if (currentBalance == null || currentBalance.compareTo(totalCost) < 0) {
                return TradeValidationResult.rejected("Insufficient funds - order rejected");
            }

            return TradeValidationResult.accepted();
        } else if (side == Order.Side.SELL) {
            long currentQuantity = accountValidation.holdingQuantity();

            if (currentQuantity < quantity) {
                return TradeValidationResult.rejected("Insufficient position quantity - order rejected");
            }

            return TradeValidationResult.accepted();
        }

        return TradeValidationResult.rejected("Unsupported order side: " + side);
    }
}

