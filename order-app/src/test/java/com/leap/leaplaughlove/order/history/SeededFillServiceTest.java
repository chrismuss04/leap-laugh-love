package com.leap.leaplaughlove.order.history;

import com.leap.leaplaughlove.common.security.JwtService;
import com.leap.leaplaughlove.order.account.Account;
import com.leap.leaplaughlove.order.account.AccountRepository;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.position.PositionMovementRepository;
import com.leap.leaplaughlove.order.quote.PriceHistoryClient;
import com.leap.leaplaughlove.order.quote.PriceHistoryClient.CandleClose;
import com.leap.leaplaughlove.order.quote.QuoteUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SeededFillService Tests")
class SeededFillServiceTest {

        private static final OffsetDateTime FILLED_AT = OffsetDateTime.of(2026, 9, 22, 14, 30, 30, 0, ZoneOffset.UTC);

        @Mock
        private OrderRepository orderRepository;
        @Mock
        private AccountRepository accountRepository;
        @Mock
        private ExecutionRepository executionRepository;
        @Mock
        private PositionMovementRepository positionMovementRepository;
        @Mock
        private FillRecorder fillRecorder;
        @Mock
        private PriceHistoryClient priceHistoryClient;
        @Mock
        private JwtService jwtService;

        private SeededFillService service;
        private Account account;
        private Instrument aapl;
        private Instrument msft;

        @BeforeEach
        void setUp() {
                service = new SeededFillService(orderRepository, accountRepository, executionRepository,
                                positionMovementRepository, fillRecorder, priceHistoryClient,
                                jwtService, new TransactionTemplate(mock(PlatformTransactionManager.class)), 0, 1);
                account = new Account(UUID.randomUUID(), UUID.randomUUID(), "ACC-TEST-01", "ACTIVE", "USD", true,
                                FILLED_AT.minusDays(30));
                aapl = new Instrument(UUID.randomUUID(), "AAPL", "Apple Inc.", "EQUITY", "NASDAQ", "USD", true);
                msft = new Instrument(UUID.randomUUID(), "MSFT", "Microsoft Corporation", "EQUITY", "NASDAQ", "USD",
                                true);
                when(accountRepository.findById(any())).thenReturn(Optional.of(account));
                when(jwtService.generateToken(any(), any())).thenReturn("token");
                when(priceHistoryClient.fetchCloses(anyString(), any(), any(), anyInt(), anyString()))
                                .thenReturn(List.of());
                when(orderRepository.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.empty());
                when(fillRecorder.recordExecution(any(), any(), any())).thenAnswer(invocation -> filledExecution(
                                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        }

        private static Execution filledExecution(Order order, BigDecimal price, OffsetDateTime time) {
                return new Execution(order, order.getQuantity(), price, Execution.Status.FILLED,
                                "Executed at market price", time);
        }

        private Order filledOrder(Instrument instrument, Order.Side side, OffsetDateTime filledAt) {
                Order order = new Order(UUID.randomUUID(), account, instrument, side, 10L, Order.Status.FILLED,
                                filledAt.minusMinutes(2), filledAt.minusMinutes(1), null, filledAt, null);
                when(orderRepository.findByIdForUpdate(order.getOrderId())).thenReturn(Optional.of(order));
                return order;
        }

        @Test
        @DisplayName("prices a fill at the close of the last minute candle that ended at or before it")
        void pricesAtLastCompletedCandle() {
                Order order = filledOrder(aapl, Order.Side.BUY, FILLED_AT);
                when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED))
                                .thenReturn(List.of(order));
                when(priceHistoryClient.fetchCloses(eq("AAPL"), any(), eq(FILLED_AT), eq(60), eq("token")))
                                .thenReturn(List.of(
                                                // Minute buckets, epoch-aligned: the fill at 14:30:30 falls inside the
                                                // 14:30 bucket.
                                                new CandleClose(FILLED_AT.withSecond(0).minusMinutes(2),
                                                                new BigDecimal("460.100000")),
                                                new CandleClose(FILLED_AT.withSecond(0).minusMinutes(1),
                                                                new BigDecimal("461.234567")),
                                                // Still open at the fill: its close is a later price than the fill saw.
                                                new CandleClose(FILLED_AT.withSecond(0),
                                                                new BigDecimal("470.000000"))));

                assertEquals(0, service.bookPendingFills());

                verify(fillRecorder).recordExecution(order, new BigDecimal("461.2346"), FILLED_AT);
        }

        @Test
        @DisplayName("falls back to wider candles when the fill is older than the fine-grained history")
        void fallsBackToWiderCandles() {
                OffsetDateTime filledAt = FILLED_AT.minusDays(120);
                Order order = filledOrder(msft, Order.Side.SELL, filledAt);
                when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED))
                                .thenReturn(List.of(order));
                when(priceHistoryClient.fetchCloses(eq("MSFT"), any(), eq(filledAt), eq(86400), eq("token")))
                                .thenReturn(List.of(
                                                new CandleClose(filledAt.minusDays(1).withHour(0).withMinute(0)
                                                                .withSecond(0), new BigDecimal("310.5"))));

                assertEquals(0, service.bookPendingFills());

                verify(fillRecorder).recordExecution(order, new BigDecimal("310.5000"), filledAt);
        }

        @Test
        @DisplayName("an unpriceable fill holds back later fills of the same holding, but not other holdings")
        void unpricedFillBlocksOnlyItsHolding() {
                Order firstAapl = filledOrder(aapl, Order.Side.BUY, FILLED_AT.minusHours(2));
                Order laterAapl = filledOrder(aapl, Order.Side.SELL, FILLED_AT.minusHours(1));
                Order msftBuy = filledOrder(msft, Order.Side.BUY, FILLED_AT);
                when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED))
                                .thenReturn(List.of(firstAapl, laterAapl, msftBuy));
                when(priceHistoryClient.fetchCloses(eq("MSFT"), any(), any(), eq(60), anyString())).thenReturn(List.of(
                                new CandleClose(FILLED_AT.minusSeconds(60), new BigDecimal("305"))));

                assertEquals(2, service.bookPendingFills());

                verify(fillRecorder).recordExecution(msftBuy, new BigDecimal("305.0000"), FILLED_AT);
                verify(fillRecorder, never()).recordExecution(eq(firstAapl), any(), any());
                verify(fillRecorder, never()).recordExecution(eq(laterAapl), any(), any());
        }

        @Test
        @DisplayName("a fill that can't be settled holds back its holding without stopping the others")
        void unsettleableFillBlocksOnlyItsHolding() {
                Order aaplSell = filledOrder(aapl, Order.Side.SELL, FILLED_AT.minusHours(2));
                Order laterAapl = filledOrder(aapl, Order.Side.BUY, FILLED_AT.minusHours(1));
                Order msftBuy = filledOrder(msft, Order.Side.BUY, FILLED_AT);
                when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED))
                                .thenReturn(List.of(aaplSell, laterAapl, msftBuy));
                when(priceHistoryClient.fetchCloses(anyString(), any(), any(), eq(60), anyString()))
                                .thenAnswer(invocation -> {
                                        OffsetDateTime to = invocation.getArgument(2);
                                        return List.of(new CandleClose(to.minusSeconds(60), new BigDecimal("300")));
                                });
                when(fillRecorder.settle(eq(aaplSell), any(), anyString()))
                                .thenThrow(new IllegalStateException("Cannot settle sell: position does not exist"));

                assertEquals(2, service.bookPendingFills());

                verify(fillRecorder, never()).recordPositionMovement(eq(aaplSell), any());
                verify(fillRecorder, never()).recordExecution(eq(laterAapl), any(), any());
                verify(fillRecorder).recordExecution(msftBuy, new BigDecimal("300.0000"), FILLED_AT);
        }

        @Test
        @DisplayName("market data being down leaves the fill pending instead of failing")
        void marketDataDownLeavesFillPending() {
                Order order = filledOrder(aapl, Order.Side.BUY, FILLED_AT);
                when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED))
                                .thenReturn(List.of(order));
                when(priceHistoryClient.fetchCloses(anyString(), any(), any(), anyInt(), anyString()))
                                .thenThrow(new QuoteUnavailableException("down", null));

                assertEquals(1, service.bookPendingFills());

                verify(fillRecorder, never()).recordExecution(any(), any(), any());
        }

        @Test
        @DisplayName("a fill booked by another instance in the meantime is not booked twice")
        void skipsFillBookedConcurrently() {
                Order order = filledOrder(aapl, Order.Side.BUY, FILLED_AT);
                when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED))
                                .thenReturn(List.of(order));
                when(priceHistoryClient.fetchCloses(eq("AAPL"), any(), any(), eq(60), anyString())).thenReturn(List.of(
                                new CandleClose(FILLED_AT.minusSeconds(60), new BigDecimal("461"))));
                when(positionMovementRepository.existsByOrderId(order.getOrderId())).thenReturn(true);

                service.bookPendingFills();

                verify(fillRecorder, never()).recordExecution(any(), any(), any());
                verify(fillRecorder, never()).settle(any(), any(), any());
        }

        @Test
        @DisplayName("settles as the order's owner, then writes the position movement")
        void settlesAsOwnerThenRecordsMovement() {
                Order order = filledOrder(aapl, Order.Side.BUY, FILLED_AT);
                when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED))
                                .thenReturn(List.of(order));
                when(priceHistoryClient.fetchCloses(eq("AAPL"), any(), any(), eq(60), anyString())).thenReturn(List.of(
                                new CandleClose(FILLED_AT.minusSeconds(60), new BigDecimal("461"))));
                when(jwtService.generateToken(account.getClientId(), null)).thenReturn("owner-token");

                assertEquals(0, service.bookPendingFills());

                // No request is behind a seeded fill, so the token can't be forwarded from one.
                verify(fillRecorder).settle(eq(order), any(Execution.class), eq("owner-token"));
                verify(fillRecorder).recordPositionMovement(eq(order), any(Execution.class));
        }

        @Test
        @DisplayName("a fill whose settling failed after its execution was written is settled with that execution")
        void resumesWithExistingExecution() {
                Order order = filledOrder(aapl, Order.Side.BUY, FILLED_AT);
                Execution earlier = filledExecution(order, new BigDecimal("461.0000"), FILLED_AT);
                when(orderRepository.findWithoutPositionMovementByStatus(Order.Status.FILLED))
                                .thenReturn(List.of(order));
                when(priceHistoryClient.fetchCloses(eq("AAPL"), any(), any(), eq(60), anyString())).thenReturn(List.of(
                                new CandleClose(FILLED_AT.minusSeconds(60), new BigDecimal("461"))));
                when(executionRepository.findFirstByOrder_OrderIdAndStatus(order.getOrderId(), Execution.Status.FILLED))
                                .thenReturn(Optional.of(earlier));

                assertEquals(0, service.bookPendingFills());

                verify(fillRecorder, never()).recordExecution(any(), any(), any());
                verify(fillRecorder).settle(eq(order), eq(earlier), anyString());
                verify(fillRecorder).recordPositionMovement(order, earlier);
        }
}
