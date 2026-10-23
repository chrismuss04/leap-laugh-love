package com.leap.leaplaughlove.order.template;

import com.leap.leaplaughlove.order.order.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A client's saved ticket setup. Prices are stored as percent offsets from the price the side
 * fills at, so the template fits whatever the market is on the day it is applied.
 */
@Entity
@Table(name = "order_templates", schema = "trading")
public class OrderTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false)
    private Order.Side side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false)
    private Order.OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_in_force", nullable = false)
    private Order.TimeInForce timeInForce;

    @Column(name = "limit_offset_pct", precision = 7, scale = 2)
    private BigDecimal limitOffsetPercent;

    @Column(name = "stop_offset_pct", precision = 7, scale = 2)
    private BigDecimal stopOffsetPercent;

    @Column(name = "trail_percent", precision = 5, scale = 2)
    private BigDecimal trailPercent;

    @Column(name = "take_profit_pct", precision = 7, scale = 2)
    private BigDecimal takeProfitPercent;

    @Column(name = "stop_loss_pct", precision = 5, scale = 2)
    private BigDecimal stopLossPercent;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Protected no-argument constructor for JPA. */
    protected OrderTemplate() {
    }

    /**
     * Creates a template from an already validated request.
     * @param clientId the owner
     * @param request what to save
     * @param createdAt when it is saved
     */
    OrderTemplate(UUID clientId, OrderTemplateRequest request, OffsetDateTime createdAt) {
        this.clientId = clientId;
        this.name = request.name().trim();
        this.side = request.side();
        this.orderType = request.orderType();
        this.timeInForce = request.timeInForce() != null ? request.timeInForce() : Order.TimeInForce.DAY;
        this.limitOffsetPercent = request.limitOffsetPercent();
        this.stopOffsetPercent = request.stopOffsetPercent();
        this.trailPercent = request.trailPercent();
        this.takeProfitPercent = request.takeProfitPercent();
        this.stopLossPercent = request.stopLossPercent();
        this.createdAt = createdAt;
    }

    /**
     * Returns the template's ID.
     * @return the template's ID
     */
    public UUID getTemplateId() { return templateId; }
    /**
     * Returns the client who owns it.
     * @return the client who owns it
     */
    public UUID getClientId() { return clientId; }
    /**
     * Returns its name, unique among the client's templates.
     * @return its name, unique among the client's templates
     */
    public String getName() { return name; }
    /**
     * Returns BUY or SELL.
     * @return BUY or SELL
     */
    public Order.Side getSide() { return side; }
    /**
     * Returns the order type to set up.
     * @return the order type to set up
     */
    public Order.OrderType getOrderType() { return orderType; }
    /**
     * Returns DAY or GTC.
     * @return DAY or GTC
     */
    public Order.TimeInForce getTimeInForce() { return timeInForce; }
    /**
     * Returns the limit price as a signed percent from the market, for LIMIT and STOP_LIMIT.
     * @return the limit price as a signed percent from the market, for LIMIT and STOP_LIMIT
     */
    public BigDecimal getLimitOffsetPercent() { return limitOffsetPercent; }
    /**
     * Returns the stop price as a signed percent from the market, for STOP and STOP_LIMIT.
     * @return the stop price as a signed percent from the market, for STOP and STOP_LIMIT
     */
    public BigDecimal getStopOffsetPercent() { return stopOffsetPercent; }
    /**
     * Returns the trail in percent, for TRAILING_STOP.
     * @return the trail in percent, for TRAILING_STOP
     */
    public BigDecimal getTrailPercent() { return trailPercent; }
    /**
     * Returns a bracket's take-profit, percent above the entry price.
     * @return a bracket's take-profit, percent above the entry price
     */
    public BigDecimal getTakeProfitPercent() { return takeProfitPercent; }
    /**
     * Returns a bracket's stop-loss, percent below the entry price.
     * @return a bracket's stop-loss, percent below the entry price
     */
    public BigDecimal getStopLossPercent() { return stopLossPercent; }
    /**
     * Returns when it was saved.
     * @return when it was saved
     */
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
