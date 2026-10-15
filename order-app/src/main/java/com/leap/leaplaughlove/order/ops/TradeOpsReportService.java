package com.leap.leaplaughlove.order.ops;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Trading Operations' exception reports across every client: why orders were rejected, and which
 * orders never reached a final status.
 */
@Service
public class TradeOpsReportService {

    private final TradeOpsRepository tradeOpsRepository;
    private final Duration stuckAfter;
    private final Clock clock;

    @Autowired
    public TradeOpsReportService(TradeOpsRepository tradeOpsRepository,
                                 @Value("${order.ops.stuck-after-minutes:5}") long stuckAfterMinutes) {
        this(tradeOpsRepository, Duration.ofMinutes(stuckAfterMinutes), Clock.systemUTC());
    }

    TradeOpsReportService(TradeOpsRepository tradeOpsRepository, Duration stuckAfter, Clock clock) {
        this.tradeOpsRepository = tradeOpsRepository;
        this.stuckAfter = stuckAfter;
        this.clock = clock;
    }

    /**
     * Orders submitted in the period (from inclusive, to exclusive) and rejected, by reason.
     * @throws IllegalArgumentException if the period is missing, or from is not before to
     */
    public List<RejectionCount> rejectionsByReason(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Give a time period to report on");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        return tradeOpsRepository.countRejectionsByReason(from, to);
    }

    /**
     * Orders still SUBMITTED or ACCEPTED longer than order.ops.stuck-after-minutes after they
     * were submitted, oldest first. A market order executes within seconds, so these are waiting
     * on something that failed.
     */
    public List<TradeSummary> stuckOrders() {
        return tradeOpsRepository.findAwaitingExecutionSince(OffsetDateTime.now(clock).minus(stuckAfter));
    }
}
