package com.leap.leaplaughlove.order.ops;

import com.leap.leaplaughlove.order.execution.ExecutionQuote;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.CashEntryRecord;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.ExecutionRecord;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.OrderRecord;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.PositionMovementRecord;
import com.leap.leaplaughlove.order.ops.TradeOpsRepository.TradeSearch;
import com.leap.leaplaughlove.order.order.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Finds trades and reconstructs one step by step from the trading audit records.
 */
@Service
public class TradeTimelineService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final TradeOpsRepository tradeOpsRepository;

    public TradeTimelineService(TradeOpsRepository tradeOpsRepository) {
        this.tradeOpsRepository = tradeOpsRepository;
    }

    /**
     * Needs a period (from inclusive, to exclusive) and the client, by id or email; the rest is optional.
     * @throws IllegalArgumentException if the period or client is missing, or from is not before to
     */
    public List<TradeSummary> search(TradeSearch criteria) {
        return tradeOpsRepository.search(validated(criteria));
    }

    /**
     * How many orders the same search finds, by outcome: every match, not only those
     * {@link #search} returns. Needs the same criteria.
     * @throws IllegalArgumentException if the period or client is missing, or from is not before to
     */
    public OrderCounts countByStatus(TradeSearch criteria) {
        return tradeOpsRepository.countByStatus(validated(criteria));
    }

    /** @throws ResponseStatusException 404 if there is no such order */
    public TradeRecords getRecords(UUID orderId) {
        return new TradeRecords(findOrder(orderId), tradeOpsRepository.findExecutions(orderId),
                tradeOpsRepository.findCashEntries(orderId), tradeOpsRepository.findPositionMovements(orderId));
    }

    /** @throws ResponseStatusException 404 if there is no such order */
    public TradeTimeline getTimeline(UUID orderId) {
        OrderRecord order = findOrder(orderId);
        TradeSummary trade = order.summary();
        Order.Side side = Order.Side.valueOf(trade.side());

        List<TimelineStep> steps = new ArrayList<>();
        steps.add(new TimelineStep(trade.submittedAt(), TimelineStepType.SUBMITTED, submitted(order)));
        if (order.acceptedAt() != null) {
            steps.add(new TimelineStep(order.acceptedAt(), TimelineStepType.ACCEPTED, "Order accepted after validation"));
        }
        if (order.rejectedAt() != null) {
            steps.add(new TimelineStep(order.rejectedAt(), TimelineStepType.REJECTED,
                    "Order rejected: " + order.rejectionReason()));
        }

        for (ExecutionRecord execution : tradeOpsRepository.findExecutions(orderId)) {
            boolean filled = "FILLED".equals(execution.status());
            if (execution.quote() != null) {
                OffsetDateTime quotedAt = execution.quote().getQuotedAt() != null
                        ? execution.quote().getQuotedAt() : execution.executedAt();
                steps.add(new TimelineStep(quotedAt, TimelineStepType.PRICED, priced(order, side, execution.quote())));
            } else if (filled) {
                steps.add(new TimelineStep(execution.executedAt(), TimelineStepType.PRICED,
                        "Market quote not recorded for this execution (placed before quotes were stored, or a seeded fill)"));
            }
            if (filled) {
                steps.add(new TimelineStep(execution.executedAt(), TimelineStepType.EXECUTED,
                        "Executed " + execution.fillQuantity() + " at " + money(execution.fillPrice())
                                + " (execution " + execution.executionId() + ")"));
            } else if (!Objects.equals(execution.reason(), order.rejectionReason())) {
                // Skips the REJECTED execution a validation rejection writes: the REJECTED step covers it.
                steps.add(new TimelineStep(execution.executedAt(), TimelineStepType.EXECUTION_REJECTED,
                        "Execution rejected: " + execution.reason()));
            }
        }

        for (CashEntryRecord cash : tradeOpsRepository.findCashEntries(orderId)) {
            steps.add(new TimelineStep(cash.createdAt(), TimelineStepType.CASH_SETTLED,
                    "Cash ledger " + cash.entryType() + ": " + signedMoney(cash.amount()) + " " + cash.currency()));
        }
        for (PositionMovementRecord movement : tradeOpsRepository.findPositionMovements(orderId)) {
            steps.add(new TimelineStep(movement.createdAt(), TimelineStepType.POSITION_UPDATED,
                    "Holding " + movement.movementType() + ": " + signed(movement.quantityDelta()) + " "
                            + trade.symbol() + ", cost " + signedMoney(movement.costDelta())));
        }
        if (order.filledAt() != null) {
            steps.add(new TimelineStep(order.filledAt(), TimelineStepType.FILLED, "Order filled"));
        }

        steps.sort(Comparator.comparing(TimelineStep::at).thenComparing(TimelineStep::type));
        return new TradeTimeline(trade, order.quotedPrice(), order.maxSlippagePercent(), steps);
    }

    private OrderRecord findOrder(UUID orderId) {
        return tradeOpsRepository.findOrder(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + orderId));
    }

    private static TradeSearch validated(TradeSearch criteria) {
        TradeSearch search = new TradeSearch(criteria.orderId(), criteria.clientId(),
                blankToNull(criteria.clientEmail()), criteria.accountId(), blankToNull(criteria.accountNumber()),
                blankToNull(criteria.symbol()), criteria.side(), criteria.status(), criteria.from(), criteria.to());
        if (search.from() == null || search.to() == null) {
            throw new IllegalArgumentException("Give a time period to search");
        }
        if (search.clientId() == null && search.clientEmail() == null) {
            throw new IllegalArgumentException("Give a client id or client email to search");
        }
        if (!search.from().isBefore(search.to())) {
            throw new IllegalArgumentException("from must be before to");
        }
        return search;
    }

    private static String submitted(OrderRecord order) {
        TradeSummary trade = order.summary();
        StringBuilder text = new StringBuilder()
                .append(trade.side()).append(' ').append(trade.quantity()).append(' ').append(trade.symbol())
                .append(" placed in account ").append(trade.accountNumber())
                .append(" by ").append(trade.clientEmail());
        if (order.quotedPrice() != null) {
            text.append("; client was quoted ").append(money(order.quotedPrice()));
        }
        if (order.maxSlippagePercent() != null) {
            text.append(" with a ").append(percent(order.maxSlippagePercent())).append(" price tolerance");
        }
        return text.toString();
    }

    // Same price rule and tolerance arithmetic as order submission, so it matches the actual decision.
    private static String priced(OrderRecord order, Order.Side side, ExecutionQuote quote) {
        StringBuilder text = new StringBuilder("Market quote")
                .append(quote.getExchange() != null ? " from " + quote.getExchange() : "")
                .append(": bid ").append(money(quote.getBid()))
                .append(", ask ").append(money(quote.getAsk()))
                .append(", last ").append(money(quote.getLast()));
        BigDecimal price = quote.executablePrice(side);
        if (price == null) {
            return text.append("; no usable price for a ").append(side).toString();
        }
        text.append("; a ").append(side).append(" prices at ").append(money(price));
        BigDecimal quoted = order.quotedPrice();
        if (quoted != null) {
            BigDecimal move = price.subtract(quoted).abs();
            text.append(", ").append(percent(move.multiply(HUNDRED).divide(quoted, 2, RoundingMode.HALF_UP)))
                    .append(" from the client's quoted ").append(money(quoted));
            BigDecimal tolerance = order.maxSlippagePercent();
            if (tolerance != null) {
                boolean within = move.multiply(HUNDRED).compareTo(tolerance.multiply(quoted)) <= 0;
                text.append(within ? ", within" : ", beyond").append(" the ")
                        .append(percent(tolerance)).append(" tolerance");
            }
        }
        return text.toString();
    }

    // Keeps sub-cent digits a price was stored with.
    private static String money(BigDecimal value) {
        if (value == null) {
            return "n/a";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return "$" + value.setScale(Math.max(2, stripped.scale()), RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String signedMoney(BigDecimal value) {
        return (value.signum() < 0 ? "-" : "+") + money(value.abs());
    }

    private static String signed(long value) {
        return value < 0 ? String.valueOf(value) : "+" + value;
    }

    private static String percent(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
