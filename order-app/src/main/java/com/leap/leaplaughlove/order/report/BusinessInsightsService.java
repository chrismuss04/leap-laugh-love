package com.leap.leaplaughlove.order.report;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Builds commercial analysts' business insights: traded value, average order value, new
 * registrations, active clients and the most active instruments over a period, alongside the
 * same figures for the period before it.
 */
@Service
public class BusinessInsightsService {

    /** How many of the most active instruments the insights list. */
    static final int TOP_INSTRUMENTS = 3;

    private final BusinessInsightsRepository businessInsightsRepository;

    /**
     * Constructs an instance of BusinessInsightsService.
     * @param businessInsightsRepository the repository the figures are read from
     */
    public BusinessInsightsService(BusinessInsightsRepository businessInsightsRepository) {
        this.businessInsightsRepository = businessInsightsRepository;
    }

    /**
     * Builds the business insights for a period.
     * @param from the first UTC day of the period, inclusive
     * @param to the last UTC day of the period, inclusive
     * @return the insights; their figures are zero when nothing happened in the period
     * @throws IllegalArgumentException if the period is invalid
     */
    public BusinessInsights getInsights(LocalDate from, LocalDate to) {
        ActivityReportService.requireValidPeriod(from, to);

        // The previous period is as long as this one and ends the day before it starts.
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate previousFrom = from.minusDays(days);
        LocalDate previousTo = from.minusDays(1);

        OffsetDateTime start = startOfDay(from);
        OffsetDateTime end = startOfDay(to.plusDays(1));
        return new BusinessInsights(from, to, previousFrom, previousTo,
                businessInsightsRepository.findFigures(start, end),
                businessInsightsRepository.findFigures(startOfDay(previousFrom), start),
                businessInsightsRepository.findTopInstruments(start, end, TOP_INSTRUMENTS));
    }

    private static OffsetDateTime startOfDay(LocalDate day) {
        return day.atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
