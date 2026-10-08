package com.leap.leaplaughlove.order.report;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controller for commercial analysts' activity reports. Access is limited to the
 * COMMERCIAL_ANALYST role by OrderSecurityConfig.
 */
@RestController
@RequestMapping("/api/order/reports")
public class ActivityReportController {

    private final ActivityReportService activityReportService;

    /**
     * Constructs an instance of ActivityReportController with the specified service.
     * @param activityReportService the service used to build activity reports
     */
    public ActivityReportController(ActivityReportService activityReportService) {
        this.activityReportService = activityReportService;
    }

    /**
     * GET HTTP endpoint for an activity report.
     * @param from the first UTC day of the period (yyyy-MM-dd), inclusive
     * @param to the last UTC day of the period (yyyy-MM-dd), inclusive
     * @param granularity DAILY or WEEKLY; defaults to DAILY
     * @param instrumentId optional instrument to report on; all instruments when omitted
     * @return the activity report, with empty buckets when nothing traded in the period
     */
    @GetMapping("/activity")
    public ActivityReport getActivityReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "DAILY") ReportGranularity granularity,
            @RequestParam(required = false) UUID instrumentId) {
        return activityReportService.getActivityReport(from, to, granularity, instrumentId);
    }

    /**
     * GET HTTP endpoint for the instruments a report can be filtered by.
     * @return every instrument, ordered by symbol
     */
    @GetMapping("/instruments")
    public List<ReportInstrument> getInstruments() {
        return activityReportService.getInstruments();
    }

    /**
     * Handles a missing required parameter by returning a BAD_REQUEST response.
     * @param ex the exception thrown for the missing parameter
     * @return a map containing the error message
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleMissingParameter(MissingServletRequestParameterException ex) {
        return Map.of("error", "BAD_REQUEST", "message", ex.getParameterName() + " is required");
    }

    /**
     * Handles a malformed date, granularity or instrument ID by returning a BAD_REQUEST response.
     * @param ex the exception thrown for the malformed parameter
     * @return a map containing the error message
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleMalformedParameter(MethodArgumentTypeMismatchException ex) {
        return Map.of("error", "BAD_REQUEST", "message", ex.getName() + " is not valid");
    }
}
