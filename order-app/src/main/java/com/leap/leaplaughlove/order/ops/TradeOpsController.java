package com.leap.leaplaughlove.order.ops;

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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trade reconstruction for Trading Operations; OrderSecurityConfig limits it to that role.
 */
@RestController
@RequestMapping("/api/order/ops/orders")
public class TradeOpsController {

    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final TradeTimelineService tradeTimelineService;

    public TradeOpsController(TradeTimelineService tradeTimelineService) {
        this.tradeTimelineService = tradeTimelineService;
    }

    /** Needs at least one criterion. from and to are inclusive UTC days. */
    @GetMapping
    public List<TradeSummary> search(
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) String clientEmail,
            @RequestParam(required = false) String accountNumber,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return tradeTimelineService.search(orderId, clientEmail, accountNumber, symbol, from, to);
    }

    @GetMapping("/{orderId}/timeline")
    public TradeTimeline getTimeline(@PathVariable UUID orderId) {
        return tradeTimelineService.getTimeline(orderId);
    }

    @GetMapping("/{orderId}/timeline.csv")
    public ResponseEntity<String> downloadTimelineCsv(@PathVariable UUID orderId) {
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
