package com.leap.leaplaughlove.trading.order;

import com.leap.leaplaughlove.trading.account.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderHistoryServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private OrderHistoryService orderHistoryService;

    @BeforeEach
    void setUp() {
        orderHistoryService = new OrderHistoryService(orderRepository);
    }

    @Test
    @DisplayName("getOrderHistory maps Order entities to OrderHistoryItem projection correctly")
    void testGetOrderHistorySuccess() {
        UUID clientId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Account account = new Account(UUID.randomUUID(), clientId, "ACCT-123", "ACTIVE", "USD", true, OffsetDateTime.now());
        Instrument instrument = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY");
        OffsetDateTime now = OffsetDateTime.now();

        Order order = new Order(
                orderId, account, instrument, Order.Side.BUY, Order.Type.MARKET,
                new BigDecimal("10.5000"), null, Order.Status.FILLED, now, now.plusSeconds(5));

        when(orderRepository.findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(clientId, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(order)));

        Page<OrderHistoryItem> result = orderHistoryService.getOrderHistory(clientId, 0, 20);

        assertEquals(1, result.getTotalElements());
        OrderHistoryItem item = result.getContent().get(0);
        assertEquals(orderId, item.orderId());
        assertEquals("AAPL", item.symbol());
        assertEquals("BUY", item.side());
        assertEquals(new BigDecimal("10.5000"), item.quantity());
        assertEquals("FILLED", item.status());
        assertEquals(now, item.submittedAt());
        assertEquals(now.plusSeconds(5), item.filledAt());

        verify(orderRepository).findByAccount_ClientIdOrderBySubmittedAtDescOrderIdDesc(clientId, PageRequest.of(0, 20));
    }

    @Test
    @DisplayName("getOrderHistory throws IllegalArgumentException when page is negative")
    void testNegativePageThrows() {
        UUID clientId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> orderHistoryService.getOrderHistory(clientId, -1, 20));
    }

    @Test
    @DisplayName("getOrderHistory throws IllegalArgumentException when size exceeds max")
    void testExcessiveSizeThrows() {
        UUID clientId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> orderHistoryService.getOrderHistory(clientId, 0, 101));
    }
}
