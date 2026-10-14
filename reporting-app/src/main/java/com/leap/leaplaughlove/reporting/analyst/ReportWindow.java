package com.leap.leaplaughlove.reporting.analyst;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

/**
 * The run of whole UTC months a report covers, first to last inclusive.
 * @param firstMonth the first month in the window
 * @param lastMonth the last month in the window
 */
public record ReportWindow(YearMonth firstMonth, YearMonth lastMonth) {

    /** How many months the analyst reports look back over. */
    static final int TRAILING_MONTHS = 12;

    /**
     * The last {@value #TRAILING_MONTHS} complete UTC months before today. The current month is
     * left out: half a month of activity would read as a drop in every report.
     * @param clock the clock giving today's date
     * @return the window ending with last month
     */
    public static ReportWindow trailingMonths(Clock clock) {
        YearMonth current = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        return new ReportWindow(current.minusMonths(TRAILING_MONTHS), current.minusMonths(1));
    }

    /**
     * The same window started earlier, for reports that compare against months before it.
     * @param months how many months earlier to start
     * @return the widened window
     */
    public ReportWindow startingEarlier(int months) {
        return new ReportWindow(firstMonth.minusMonths(months), lastMonth);
    }

    /**
     * Every month in the window, oldest first.
     * @return the months from first to last
     */
    @JsonIgnore
    public List<YearMonth> months() {
        return Stream.iterate(firstMonth, month -> !month.isAfter(lastMonth), month -> month.plusMonths(1)).toList();
    }

    /**
     * The window's first day.
     * @return the first day of the first month, inclusive
     */
    @JsonIgnore
    public LocalDate startDate() {
        return firstMonth.atDay(1);
    }

    /**
     * The day after the window, so ranges can be half-open.
     * @return the first day of the month after the last, exclusive
     */
    @JsonIgnore
    public LocalDate endDate() {
        return lastMonth.plusMonths(1).atDay(1);
    }

    /**
     * The window's first instant, for querying timestamps.
     * @return midnight UTC on the first day, inclusive
     */
    @JsonIgnore
    public OffsetDateTime startInstant() {
        return startDate().atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /**
     * The instant the window ends, for querying timestamps.
     * @return midnight UTC on the day after the window, exclusive
     */
    @JsonIgnore
    public OffsetDateTime endInstant() {
        return endDate().atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
