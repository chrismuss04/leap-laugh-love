package com.leap.leaplaughlove.reporting.analyst;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves commercial analysts' long-range reports. Each covers the last twelve complete UTC months
 * and takes no parameters; the response names the months it covers. Analysts only: see
 * ReportingSecurityConfig.
 */
@RestController
@RequestMapping("/api/reporting/analyst")
public class AnalystReportController {

    private final AnalystReportService reports;

    /**
     * Constructs an instance of AnalystReportController.
     * @param reports the service that builds the reports
     */
    public AnalystReportController(AnalystReportService reports) {
        this.reports = reports;
    }

    /**
     * Volume, filled orders, average trade value, active clients and registrations per month,
     * with month-over-month and year-over-year change.
     * @return the scorecard
     */
    @GetMapping("/scorecard")
    public ScorecardReport scorecard() {
        return reports.getScorecard();
    }

    /**
     * Active clients per month split into new, returning and reactivated, with those who lapsed.
     * @return the client activity report
     */
    @GetMapping("/client-activity")
    public ClientActivityReport clientActivity() {
        return reports.getClientActivity();
    }

    /**
     * Each sign-up month's share of clients active in each month after.
     * @return the retention report
     */
    @GetMapping("/retention")
    public RetentionReport retention() {
        return reports.getRetention();
    }

    /**
     * Each sign-up month's clients who placed an order within 30 days and at all, and how soon.
     * @return the activation report
     */
    @GetMapping("/activation")
    public ActivationReport activation() {
        return reports.getActivation();
    }

    /**
     * Each month's share of volume from the top 1, 10 and 20 percent of trading clients.
     * @return the concentration report
     */
    @GetMapping("/concentration")
    public ConcentrationReport concentration() {
        return reports.getConcentration();
    }

    /**
     * Each month's filled orders by value band, with mean and median order value.
     * @return the order size report
     */
    @GetMapping("/order-sizes")
    public OrderSizeReport orderSizes() {
        return reports.getOrderSizes();
    }
}
