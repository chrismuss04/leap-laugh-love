package com.leap.leaplaughlove.order.template;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A saved ticket setup, as listed to its owner.
 *
 * @param templateId the template
 * @param name its name
 * @param side BUY or SELL
 * @param orderType MARKET, LIMIT, STOP, STOP_LIMIT or TRAILING_STOP
 * @param timeInForce DAY or GTC
 * @param limitOffsetPercent the limit price as signed percent from the market, if the type has one
 * @param stopOffsetPercent the stop price as signed percent from the market, if the type has one
 * @param trailPercent a trailing stop's trail in percent
 * @param takeProfitPercent a bracket's take-profit, percent above the entry price
 * @param stopLossPercent a bracket's stop-loss, percent below the entry price
 * @param createdAt when it was saved
 */
public record OrderTemplateItem(
        UUID templateId,
        String name,
        String side,
        String orderType,
        String timeInForce,
        BigDecimal limitOffsetPercent,
        BigDecimal stopOffsetPercent,
        BigDecimal trailPercent,
        BigDecimal takeProfitPercent,
        BigDecimal stopLossPercent,
        OffsetDateTime createdAt
) {
    /**
     * Describes a template.
     * @param template the template
     * @return its item
     */
    public static OrderTemplateItem of(OrderTemplate template) {
        return new OrderTemplateItem(
                template.getTemplateId(), template.getName(), template.getSide().name(),
                template.getOrderType().name(), template.getTimeInForce().name(),
                template.getLimitOffsetPercent(), template.getStopOffsetPercent(), template.getTrailPercent(),
                template.getTakeProfitPercent(), template.getStopLossPercent(), template.getCreatedAt());
    }
}
