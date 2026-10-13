package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.order.Order.OrderType;
import com.leap.leaplaughlove.order.order.Order.Side;
import com.leap.leaplaughlove.order.order.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ReservationService: what open orders hold back")
class ReservationServiceTest {

    @Test
    @DisplayName("a limit or stop-limit buy holds its limit times quantity")
    void limitBuysHoldTheirLimit() {
        BigDecimal perShare = ReservationService.reservePricePerShare(OrderType.LIMIT, new BigDecimal("145.00"), null);

        assertEquals(new BigDecimal("1450.00"), ReservationService.reservedCash(Side.BUY, 10, perShare));
        assertEquals(new BigDecimal("145.00"), ReservationService.reservePricePerShare(
                OrderType.STOP_LIMIT, new BigDecimal("145.00"), new BigDecimal("144.00")));
    }

    @Test
    @DisplayName("a stop buy holds 5% over its stop, since it can fill above it")
    void stopBuysHoldABuffer() {
        BigDecimal perShare = ReservationService.reservePricePerShare(OrderType.STOP, null, new BigDecimal("100.00"));

        assertEquals(new BigDecimal("105.0000"), perShare);
        assertEquals(new BigDecimal("1050.00"), ReservationService.reservedCash(Side.BUY, 10, perShare));
    }

    @Test
    @DisplayName("a sell holds no cash")
    void sellsHoldNoCash() {
        assertNull(ReservationService.reservedCash(Side.SELL, 10, new BigDecimal("100.00")));
    }

    @Test
    @DisplayName("sums the account's holds, treating none as zero")
    void sumsHolds() {
        OrderRepository orders = mock(OrderRepository.class);
        UUID accountId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        when(orders.sumReservedCash(eq(accountId), any())).thenReturn(null);
        when(orders.sumOpenSellQuantity(eq(accountId), eq(instrumentId), any())).thenReturn(7L);

        ReservationService.Holds holds = new ReservationService(orders).holdsFor(accountId, instrumentId, null);

        assertEquals(BigDecimal.ZERO, holds.cash());
        assertEquals(7L, holds.shares());
    }

    @Test
    @DisplayName("takes holds out of the cash and shares an order may use")
    void availableSubtractsHolds() {
        AccountValidationDto account = new AccountValidationDto(true, true, new BigDecimal("1000.00"), 25L,
                "USD", "ACC-1", null);

        AccountValidationDto available = ReservationService.available(account,
                new ReservationService.Holds(new BigDecimal("400.00"), 20L));

        assertEquals(new BigDecimal("600.00"), available.cashBalance());
        assertEquals(5L, available.holdingQuantity());
        assertEquals("ACC-1", available.accountNumber());
    }

    @Test
    @DisplayName("explains a rejection that holds caused, and leaves others alone")
    void explainsHolds() {
        ReservationService.Holds holds = new ReservationService.Holds(new BigDecimal("8400"), 20L);

        assertEquals("Insufficient funds - order rejected ($8400.00 is held for your open orders)",
                ReservationService.explain("Insufficient funds - order rejected", Side.BUY, holds));
        assertEquals("Insufficient position quantity - order rejected (20 shares are held for your open sell orders)",
                ReservationService.explain("Insufficient position quantity - order rejected", Side.SELL, holds));
        assertEquals("Account is not active",
                ReservationService.explain("Account is not active", Side.BUY, holds));
        assertEquals("Insufficient funds - order rejected",
                ReservationService.explain("Insufficient funds - order rejected", Side.BUY, ReservationService.Holds.NONE));
    }
}
