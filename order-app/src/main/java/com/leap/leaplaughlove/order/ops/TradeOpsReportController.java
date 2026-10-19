package com.leap.leaplaughlove.order.ops;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Trading Operations' exception reports across every client; OrderSecurityConfig limits them to
 * that role. Like trade lookups, every request is logged before any data is read.
 */
@RestController
@RequestMapping("/api/order/ops/reports")
public class TradeOpsReportController {

    private final TradeOpsReportService tradeOpsReportService;
    private final OpsAccessLog accessLog;

    public TradeOpsReportController(TradeOpsReportService tradeOpsReportService, OpsAccessLog accessLog) {
        this.tradeOpsReportService = tradeOpsReportService;
        this.accessLog = accessLog;
    }

    /** from and to are ISO date-times bounding when the orders were submitted; from is inclusive, to exclusive. */
    @GetMapping("/rejections")
    public List<RejectionCount> rejectionsByReason(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("from", from);
        criteria.put("to", to);
        criteria.values().removeIf(Objects::isNull);
        accessLog.record(OpsAccessLog.Action.VIEW_REJECTIONS, null, criteria.toString());
        return tradeOpsReportService.rejectionsByReason(from, to);
    }

    /**
     * Orders that were submitted or accepted but never executed, across every client.
     * @return the stuck orders, oldest first
     */
    @GetMapping("/stuck-orders")
    public List<TradeSummary> stuckOrders() {
        accessLog.record(OpsAccessLog.Action.VIEW_STUCK_ORDERS, null, null);
        return tradeOpsReportService.stuckOrders();
    }

    /**
     * Answers a parameter that can't be read, such as a malformed date, with a 400.
     * @param ex the mismatch
     * @return an error body naming the parameter
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleMalformedParameter(MethodArgumentTypeMismatchException ex) {
        return Map.of("error", "BAD_REQUEST", "message", ex.getName() + " is not valid");
    }
}
