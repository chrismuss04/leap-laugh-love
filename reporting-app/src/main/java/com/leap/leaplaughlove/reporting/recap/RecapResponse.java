package com.leap.leaplaughlove.reporting.recap;

import java.time.OffsetDateTime;
import java.time.YearMonth;

/**
 * A client's recap, as the recap page receives it.
 * @param month the month it covers
 * @param generatedAt when it was built
 * @param ranked true when it was built with every other client's, so its ranking is final; false
 *               when it was worked out just for this request, before the month's job ran, and has
 *               no ranking
 * @param recap the recap itself
 */
public record RecapResponse(YearMonth month, OffsetDateTime generatedAt, boolean ranked, RecapPayload recap) {}
