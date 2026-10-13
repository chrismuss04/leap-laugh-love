package com.leap.leaplaughlove.order.working;

import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.quote.CurrentQuoteService;
import com.leap.leaplaughlove.order.validation.TradeValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("OrderModificationService account validation failures")
class OrderModificationServiceTest {

    private final OpenOrderService openOrders = mock(OpenOrderService.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final AccountClient accountClient = mock(AccountClient.class);

    private OrderModificationService service;
    private UUID clientId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        service = new OrderModificationService(openOrders, orders, accountClient,
                mock(CurrentQuoteService.class), mock(TradeValidationService.class), mock(ReservationService.class));
        clientId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        Instrument instrument = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
        Order order = new Order(orderId, UUID.randomUUID(), instrument, Order.Side.BUY, 10L, Order.Status.OPEN,
                OffsetDateTime.now(), null, null, null, null);
        order.setConditions(Order.OrderType.LIMIT, new BigDecimal("145.00"), null, Order.TimeInForce.GTC, null);
        when(openOrders.lockOwned(clientId, orderId)).thenReturn(order);
    }

    private ResponseStatusException rejected() {
        return assertThrows(ResponseStatusException.class, this::changeQuantity);
    }

    private void changeQuantity() {
        service.modify(clientId, orderId, new ModifyOrderRequest(5L, null, null, null, null, null));
    }

    @Test
    @DisplayName("passes account-app 404/403 through, turns other 4xx into 400 and an unreachable account-app into 503")
    void accountValidationFailures() {
        doThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null))
                .when(accountClient).getValidationData(any(), any());
        ResponseStatusException notFound = rejected();
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
        assertEquals("Failed to validate account", notFound.getReason());

        doThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null))
                .when(accountClient).getValidationData(any(), any());
        assertEquals(HttpStatus.FORBIDDEN, rejected().getStatusCode());

        doThrow(new HttpClientErrorException(HttpStatus.CONFLICT))
                .when(accountClient).getValidationData(any(), any());
        assertEquals(HttpStatus.BAD_REQUEST, rejected().getStatusCode());

        doThrow(new ResourceAccessException("Read timed out http://account-app:8082"))
                .when(accountClient).getValidationData(any(), any());
        ResponseStatusException unavailable = rejected();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
        assertEquals("Account service unavailable, please try again", unavailable.getReason());
    }

    @Test
    @DisplayName("lets a failure that isn't from the account-app call pass through unwrapped")
    void unexpectedFailurePassesThrough() {
        doThrow(new IllegalStateException("boom")).when(accountClient).getValidationData(any(), any());
        assertThrows(IllegalStateException.class, this::changeQuantity);
    }
}
