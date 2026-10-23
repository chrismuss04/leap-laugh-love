package com.leap.leaplaughlove.order.ops;

import com.leap.leaplaughlove.order.ops.TradeOpsRepository.TradeSearch;
import com.leap.leaplaughlove.order.order.Order;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Trade reconstruction for Trading Operations; OrderSecurityConfig limits it to that role.
 * Every lookup is logged first, including ones that turn out invalid or find nothing.
 */
@RestController
@RequestMapping("/api/order/ops/orders")
public class TradeOpsController {

    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final TradeTimelineService tradeTimelineService;
    private final OpsAccessLog accessLog;

    /**
     * Constructs a new TradeOpsController.
     * @param tradeTimelineService finds and reconstructs trades
     * @param accessLog records each lookup
     */
    public TradeOpsController(TradeTimelineService tradeTimelineService, OpsAccessLog accessLog) {
        this.tradeTimelineService = tradeTimelineService;
        this.accessLog = accessLog;
    }

    /**
     * Needs a period and the client, by id or email; the other criteria narrow it further.
     * from and to are ISO date-times; from is inclusive, to exclusive.
     * @param from the start of the period, inclusive
     * @param to the end of the period, exclusive
     * @param clientId the client, or null to give their email instead
     * @param clientEmail the client's email, ignoring case, or null to give their ID instead
     * @param accountId only this account, or null
     * @param accountNumber only the account with this number, or null
     * @param symbol only this instrument, ignoring case, or null
     * @param side only buys or only sells, or null
     * @param status only orders with this status, or null
     * @param orderId only this order, or null
     * @return the matching trades, newest first
     */
    @GetMapping
    public List<TradeSummary> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) String clientEmail,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) String accountNumber,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Order.Side side,
            @RequestParam(required = false) Order.Status status,
            @RequestParam(required = false) UUID orderId) {
        TradeSearch search = new TradeSearch(orderId, clientId, clientEmail, accountId, accountNumber,
                symbol, side, status, from, to);
        accessLog.record(OpsAccessLog.Action.SEARCH, null, criteria(search));
        return tradeTimelineService.search(search);
    }

    /**
     * How many orders the same search matches, by outcome; takes the same criteria.
     * @param from the start of the period, inclusive
     * @param to the end of the period, exclusive
     * @param clientId the client, or null to give their email instead
     * @param clientEmail the client's email, ignoring case, or null to give their ID instead
     * @param accountId only this account, or null
     * @param accountNumber only the account with this number, or null
     * @param symbol only this instrument, ignoring case, or null
     * @param side only buys or only sells, or null
     * @param status only orders with this status, or null
     * @param orderId only this order, or null
     * @return the counts by outcome
     */
    @GetMapping("/counts")
    public OrderCounts countByStatus(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) String clientEmail,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) String accountNumber,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) Order.Side side,
            @RequestParam(required = false) Order.Status status,
            @RequestParam(required = false) UUID orderId) {
        TradeSearch search = new TradeSearch(orderId, clientId, clientEmail, accountId, accountNumber,
                symbol, side, status, from, to);
        accessLog.record(OpsAccessLog.Action.VIEW_ORDER_COUNTS, null, criteria(search));
        return tradeTimelineService.countByStatus(search);
    }

    /**
     * The order's recorded fields, executions, cash entries and position movements.
     * @param orderId the order
     * @return everything recorded for the order
     */
    @GetMapping("/{orderId}")
    public TradeRecords getRecords(@PathVariable UUID orderId) {
        accessLog.record(OpsAccessLog.Action.VIEW_ORDER, orderId, null);
        return tradeTimelineService.getRecords(orderId);
    }

    /**
     * The order's records as one time-ordered sequence of steps.
     * @param orderId the order
     * @return the order's timeline
     */
    @GetMapping("/{orderId}/timeline")
    public TradeTimeline getTimeline(@PathVariable UUID orderId) {
        accessLog.record(OpsAccessLog.Action.VIEW_TIMELINE, orderId, null);
        return tradeTimelineService.getTimeline(orderId);
    }

    /**
     * The order's timeline as a CSV download.
     * @param orderId the order
     * @return the CSV, as an attachment named {@code trade-<orderId>.csv}
     */
    @GetMapping("/{orderId}/timeline.csv")
    public ResponseEntity<String> downloadTimelineCsv(@PathVariable UUID orderId) {
        accessLog.record(OpsAccessLog.Action.DOWNLOAD_CSV, orderId, null);
        String csv = TradeTimelineCsv.write(tradeTimelineService.getTimeline(orderId));
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("trade-" + orderId + ".csv").build().toString())
                .body(csv);
    }

    // The criteria given, in a fixed order, for the access log.
    private static String criteria(TradeSearch search) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("from", search.from());
        criteria.put("to", search.to());
        criteria.put("clientId", search.clientId());
        criteria.put("clientEmail", search.clientEmail());
        criteria.put("accountId", search.accountId());
        criteria.put("accountNumber", search.accountNumber());
        criteria.put("symbol", search.symbol());
        criteria.put("side", search.side());
        criteria.put("status", search.status());
        criteria.put("orderId", search.orderId());
        criteria.values().removeIf(Objects::isNull);
        return criteria.toString();
    }

    /**
     * Answers a parameter that can't be read, such as a malformed date or UUID, with a 400.
     * @param ex the mismatch
     * @return an error body naming the parameter
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleMalformedParameter(MethodArgumentTypeMismatchException ex) {
        return Map.of("error", "BAD_REQUEST", "message", ex.getName() + " is not valid");
    }
}
