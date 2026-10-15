package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Works out what an account's OPEN orders hold back, so cash or shares one open order is
 * counting on can't be spent by another order too. An open buy holds the most it could cost
 * (its {@code reserved_cash}); an open sell holds its shares.
 *
 * <p>Holds live only in order-app: account-app's balances don't know about them, so the check
 * when an open order finally fills is the last line of defence (e.g. after a withdrawal).
 */
@Service
public class ReservationService {

    /**
     * A stop buy becomes a market order, which can fill above its stop if the price jumps, so it
     * holds this much more than stop x quantity.
     */
    public static final BigDecimal STOP_BUY_BUFFER = new BigDecimal("1.05");

    /**
     * How far a market order queued for the open may fill from the price when it was queued: a buy
     * pays at most 5% more, a sell takes at least 5% less. A buy holds cash at that most.
     */
    public static final BigDecimal QUEUED_MARKET_COLLAR = new BigDecimal("0.05");

    /** Stands in for "no order to leave out" in the repository's sums. */
    private static final UUID NO_ORDER = new UUID(0L, 0L);

    /**
     * What an account's OPEN orders hold back.
     * @param cash cash held by open buys
     * @param shares shares of one instrument held by open sells
     */
    public record Holds(BigDecimal cash, long shares) {
        /** Nothing held. */
        public static final Holds NONE = new Holds(BigDecimal.ZERO, 0L);
    }

    private final OrderRepository orderRepository;

    /**
     * Constructs a ReservationService.
     * @param orderRepository sums the account's open orders
     */
    public ReservationService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Returns what the account's OPEN orders hold back.
     * @param accountId the account
     * @param instrumentId the instrument whose shares to count
     * @param excludedOrderId an order to leave out, e.g. the one being filled; null for none
     * @return the cash and shares held
     */
    public Holds holdsFor(UUID accountId, UUID instrumentId, UUID excludedOrderId) {
        UUID excluded = excludedOrderId != null ? excludedOrderId : NO_ORDER;
        BigDecimal cash = orderRepository.sumReservedCash(accountId, excluded);
        Long shares = orderRepository.sumOpenSellQuantity(accountId, instrumentId, excluded);
        return new Holds(cash != null ? cash : BigDecimal.ZERO, shares != null ? shares : 0L);
    }

    /**
     * The account's validation data with held cash and shares taken out, so validating against
     * it only counts what is actually free.
     * @param account the account's validation data from account-app
     * @param holds what its open orders hold
     * @return the same data, with cash and holdings reduced by the holds
     */
    public static AccountValidationDto available(AccountValidationDto account, Holds holds) {
        if (account == null || holds == null) {
            return account;
        }
        BigDecimal cash = account.cashBalance() != null ? account.cashBalance().subtract(holds.cash()) : null;
        return new AccountValidationDto(
                account.accountActive(),
                account.tradingEnabled(),
                cash,
                account.holdingQuantity() - holds.shares(),
                account.baseCurrency(),
                account.accountNumber(),
                account.maxSlippagePercent());
    }

    /**
     * The most one share could cost when an order on these conditions fills: its limit for a
     * LIMIT or STOP_LIMIT order, its stop plus {@link #STOP_BUY_BUFFER} for a STOP order.
     * @param type the order type (not MARKET)
     * @param limitPrice the limit price, if any
     * @param stopPrice the stop price, if any
     * @return the price per share to hold cash for
     */
    public static BigDecimal reservePricePerShare(Order.OrderType type, BigDecimal limitPrice, BigDecimal stopPrice) {
        return reservePricePerShare(type, limitPrice, stopPrice, null);
    }

    /**
     * The most one share could cost when an order on these conditions fills: its limit for a
     * LIMIT or STOP_LIMIT order, its stop plus {@link #STOP_BUY_BUFFER} for a STOP order, and its
     * collar for a market order queued for the open.
     * @param type the order type
     * @param limitPrice the limit price, if any
     * @param stopPrice the stop price, if any
     * @param collarPrice a queued market order's collar, if any
     * @return the price per share to hold cash for
     * @throws IllegalArgumentException for a market order with no collar, which never waits
     */
    public static BigDecimal reservePricePerShare(Order.OrderType type, BigDecimal limitPrice, BigDecimal stopPrice,
                                                  BigDecimal collarPrice) {
        if (type == Order.OrderType.MARKET) {
            if (collarPrice == null) {
                throw new IllegalArgumentException("Only a market order queued for the open holds cash");
            }
            return collarPrice;
        }
        if (type.hasLimit()) {
            return limitPrice;
        }
        return stopPrice.multiply(STOP_BUY_BUFFER).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * The collar for a market order queued for the open: {@link #QUEUED_MARKET_COLLAR} above the
     * ask for a buy, below the bid for a sell.
     * @param side the order's side
     * @param price the side's executable price when it is queued
     * @return the most a buy pays, or the least a sell takes, per share
     */
    public static BigDecimal collarFor(Order.Side side, BigDecimal price) {
        BigDecimal factor = side == Order.Side.BUY
                ? BigDecimal.ONE.add(QUEUED_MARKET_COLLAR)
                : BigDecimal.ONE.subtract(QUEUED_MARKET_COLLAR);
        return price.multiply(factor).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * The cash an OPEN order holds back from buying power.
     * @param side the order's side; only a buy holds cash
     * @param quantity the shares ordered
     * @param pricePerShare see {@link #reservePricePerShare}
     * @return the cash to hold, or null for a sell
     */
    public static BigDecimal reservedCash(Order.Side side, long quantity, BigDecimal pricePerShare) {
        if (side != Order.Side.BUY) {
            return null;
        }
        return pricePerShare.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Adds what open orders hold to a rejection for lack of cash or shares, so the client can
     * see why the money or shares they have aren't enough.
     * @param reason the rejection reason from validation
     * @param side the order's side
     * @param holds what the account's other open orders hold
     * @return the reason, explained when holds caused it
     */
    public static String explain(String reason, Order.Side side, Holds holds) {
        if (reason == null || holds == null) {
            return reason;
        }
        if (side == Order.Side.BUY && reason.startsWith("Insufficient funds")
                && holds.cash().compareTo(BigDecimal.ZERO) > 0) {
            return reason + " ($" + holds.cash().setScale(2, RoundingMode.HALF_UP).toPlainString()
                    + " is held for your open orders)";
        }
        if (side == Order.Side.SELL && reason.startsWith("Insufficient position") && holds.shares() > 0) {
            return reason + " (" + holds.shares() + (holds.shares() == 1 ? " share is" : " shares are")
                    + " held for your open sell orders)";
        }
        return reason;
    }
}
