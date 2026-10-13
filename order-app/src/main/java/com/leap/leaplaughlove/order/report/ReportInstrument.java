package com.leap.leaplaughlove.order.report;

import java.util.UUID;

/**
 * An instrument an activity report can be filtered by.
 * @param id the instrument ID
 * @param symbol the instrument's trading symbol
 */
public record ReportInstrument(UUID id, String symbol) {
}
