package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.quote.CurrentQuoteService;
import com.leap.leaplaughlove.order.submission.OrderSubmissionService;
import com.leap.leaplaughlove.order.validation.TradeValidationResult;
import com.leap.leaplaughlove.order.validation.TradeValidationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Edits an OPEN order in place - quantity, limit or stop price, trail, time in force - keeping its
 * ID and its place in history. The order is locked first, so an edit and {@link OrderTriggerEngine}
 * filling it can't both win, and checked as a new order would be: the account must still cover the
 * changed order's cash or shares (less what its other open orders hold), and a stop can't be moved
 * to a price the market has already passed, which would trigger the order at once. Changing the
 * quantity of one exit leg of a bracket changes the other leg's too.
 */
@Service
public class OrderModificationService {

    private final OpenOrderService openOrderService;
    private final OrderRepository orderRepository;
    private final AccountClient accountClient;
    private final CurrentQuoteService currentQuoteService;
    private final TradeValidationService tradeValidationService;
    private final ReservationService reservationService;

    /**
     * Constructs an OrderModificationService.
     * @param openOrderService finds and locks the client's open order
     * @param orderRepository locks the account while holds are counted
     * @param accountClient reads the account's cash and holdings
     * @param currentQuoteService reads the live quote a stop is checked against
     * @param tradeValidationService checks the changed order is affordable
     * @param reservationService counts what the account's other open orders hold
     */
    public OrderModificationService(OpenOrderService openOrderService, OrderRepository orderRepository,
                                    AccountClient accountClient, CurrentQuoteService currentQuoteService,
                                    TradeValidationService tradeValidationService,
                                    ReservationService reservationService) {
        this.openOrderService = openOrderService;
        this.orderRepository = orderRepository;
        this.accountClient = accountClient;
        this.currentQuoteService = currentQuoteService;
        this.tradeValidationService = tradeValidationService;
        this.reservationService = reservationService;
    }

    /**
     * Applies an edit to one of the client's open orders.
     * @param clientId the authenticated client
     * @param orderId the order to change
     * @param request what to change
     * @return the order as it now stands
     * @throws ResponseStatusException not found (404) if the client has no such order, conflict
     *         (409) if it is no longer OPEN, bad request (400) if the edit doesn't fit the order's
     *         type or the account can't cover it
     */
    @Transactional
    public OpenOrderItem modify(UUID clientId, UUID orderId, ModifyOrderRequest request) {
        // A bracket's exit legs sell the same shares, so a change locks them together, lowest ID
        // first (see OrderRepository#findGroupForUpdate), before the order itself.
        UUID group = orderRepository.findOcoGroupId(orderId);
        List<Order> linked = group != null ? orderRepository.findGroupForUpdate(group) : List.of();
        Order order = openOrderService.lockOwned(clientId, orderId);
        OpenOrderService.requireOpen(order, "changed");

        Order.OrderType type = order.getOrderType();
        Order.Side side = order.getSide();
        requireApplicable(order, request);

        long quantity = request.quantity() != null ? request.quantity() : order.getQuantity();
        BigDecimal limitPrice = request.limitPrice() != null ? request.limitPrice() : order.getLimitPrice();
        BigDecimal stopPrice = request.stopPrice() != null ? request.stopPrice() : order.getStopPrice();
        BigDecimal trailAmount = order.getTrailAmount();
        BigDecimal trailPercent = order.getTrailPercent();
        boolean trailChanged = request.trailAmount() != null || request.trailPercent() != null;
        if (trailChanged) {
            // A new trail sets the stop afresh from the best price already seen.
            trailAmount = request.trailAmount();
            trailPercent = request.trailPercent();
            stopPrice = TrailingStop.stopFor(side, order.getTrailPeak(), trailAmount, trailPercent);
        }
        Order.TimeInForce timeInForce = request.timeInForce() != null ? request.timeInForce() : order.getTimeInForce();
        OffsetDateTime expiresAt = timeInForce != order.getTimeInForce()
                ? OrderSubmissionService.expiryOf(timeInForce, OffsetDateTime.now())
                : order.getExpiresAt();

        Instrument instrument = order.getInstrument();
        boolean stopChanged = request.stopPrice() != null || trailChanged;
        if (stopChanged) {
            requireStopNotReached(order, stopPrice);
        }

        // The changed order must still be covered by what the account has free.
        AccountValidationDto account;
        try {
            account = accountClient.getValidationData(order.getAccountId(), instrument.getInstrumentId());
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to validate account: " + ex.getMessage());
        }
        orderRepository.lockAccount(order.getAccountId());
        ReservationService.Holds holds = reservationService.holdsFor(
                order.getAccountId(), instrument.getInstrumentId(), order.getOrderId());
        BigDecimal reservePrice = ReservationService.reservePricePerShare(type, limitPrice, stopPrice);
        TradeValidationResult result = tradeValidationService.validateTrade(
                ReservationService.available(account, holds), instrument, side, quantity, reservePrice);
        if (!result.isValid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    ReservationService.explain(result.reason(), side, holds));
        }

        order.modify(quantity, limitPrice, stopPrice, trailAmount, trailPercent, timeInForce, expiresAt,
                ReservationService.reservedCash(side, quantity, reservePrice));
        // Both legs sell the same shares, so a new quantity applies to the leg still working too.
        for (Order leg : linked) {
            if (!leg.getOrderId().equals(orderId) && leg.getStatus() == Order.Status.OPEN) {
                leg.syncQuantity(quantity);
            }
        }
        return OpenOrderItem.of(order);
    }

    /**
     * Refuses a field that doesn't apply to the order's type, or a stop that has already been reached.
     * @param order the open order
     * @param request the edit
     */
    private static void requireApplicable(Order order, ModifyOrderRequest request) {
        Order.OrderType type = order.getOrderType();
        if (request.limitPrice() != null && !type.hasLimit()) {
            throw badRequest("A " + describe(type) + " order has no limit price to change");
        }
        if (request.stopPrice() != null && !type.takesStopPrice()) {
            throw badRequest("A " + describe(type) + " order has no stop price to change"
                    + (type.hasTrail() ? " - change its trail instead" : ""));
        }
        if ((request.trailAmount() != null || request.trailPercent() != null) && !type.hasTrail()) {
            throw badRequest("Only a trailing stop has a trail to change");
        }
        if (request.stopPrice() != null && order.getTriggeredAt() != null) {
            throw badRequest("This order's stop price has been reached, so it can no longer be changed");
        }
    }

    /**
     * Refuses a stop that the market has already passed, which would trigger the order at once.
     * @param order the open order
     * @param stopPrice the stop it would now have
     */
    private void requireStopNotReached(Order order, BigDecimal stopPrice) {
        Order.Side side = order.getSide();
        if (stopPrice.signum() <= 0) {
            throw badRequest("That trail is larger than the price, which would put the stop at or below zero");
        }
        BigDecimal price = TriggerEvaluator.executablePrice(side,
                currentQuoteService.getCurrentQuote(order.getInstrument().getSymbol()));
        if (price == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No valid executable price found for symbol: " + order.getInstrument().getSymbol());
        }
        if (TriggerEvaluator.stopReached(side, stopPrice, price)) {
            throw badRequest(String.format("A stop of $%s must be %s the current %s of $%s, or the order would trigger at once",
                    stopPrice.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    side == Order.Side.BUY ? "above" : "below",
                    side == Order.Side.BUY ? "ask" : "bid",
                    price.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        }
    }

    private static String describe(Order.OrderType type) {
        return type.name().toLowerCase().replace('_', ' ');
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
