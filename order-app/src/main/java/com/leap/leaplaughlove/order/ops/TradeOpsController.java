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

    public TradeOpsController(TradeTimelineService tradeTimelineService, OpsAccessLog accessLog) {
        this.tradeTimelineService = tradeTimelineService;
        this.accessLog = accessLog;
    }

    /**
     * Needs a period and the client, by id or email; the other criteria narrow it further.
     * from and to are ISO date-times; from is inclusive, to exclusive.
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
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("from", from);
        criteria.put("to", to);
        criteria.put("clientId", clientId);
        criteria.put("clientEmail", clientEmail);
        criteria.put("accountId", accountId);
        criteria.put("accountNumber", accountNumber);
        criteria.put("symbol", symbol);
        criteria.put("side", side);
        criteria.put("status", status);
        criteria.put("orderId", orderId);
        criteria.values().removeIf(Objects::isNull);
        accessLog.record(OpsAccessLog.Action.SEARCH, null, criteria.toString());
        return tradeTimelineService.search(new TradeSearch(orderId, clientId, clientEmail, accountId, accountNumber,
                symbol, side, status, from, to));
    }

    @GetMapping("/{orderId}/timeline")
    public TradeTimeline getTimeline(@PathVariable UUID orderId) {
        accessLog.record(OpsAccessLog.Action.VIEW_TIMELINE, orderId, null);
        return tradeTimelineService.getTimeline(orderId);
    }

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

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleMalformedParameter(MethodArgumentTypeMismatchException ex) {
        return Map.of("error", "BAD_REQUEST", "message", ex.getName() + " is not valid");
    }
}
