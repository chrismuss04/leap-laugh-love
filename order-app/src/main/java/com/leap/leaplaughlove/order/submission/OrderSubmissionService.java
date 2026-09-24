package com.leap.leaplaughlove.order.submission;

import com.leap.leaplaughlove.order.instrument.Instrument;
import com.leap.leaplaughlove.order.instrument.InstrumentRepository;
import com.leap.leaplaughlove.order.order.Order;
import com.leap.leaplaughlove.order.order.OrderRepository;
import com.leap.leaplaughlove.order.execution.Execution;
import com.leap.leaplaughlove.order.execution.ExecutionRepository;
import com.leap.leaplaughlove.order.history.FillRecorder;
import com.leap.leaplaughlove.order.validation.TradeValidationResult;
import com.leap.leaplaughlove.order.validation.TradeValidationService;
import com.leap.leaplaughlove.order.client.AccountClient;
import com.leap.leaplaughlove.order.client.AccountValidationDto;
import com.leap.leaplaughlove.order.quote.CurrentQuoteService;
import com.leap.leaplaughlove.order.quote.QuoteSnapshot;
import com.leap.leaplaughlove.order.quote.QuoteUnavailableException;
import com.leap.leaplaughlove.order.quote.StaleQuoteException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * Service to orchestrate the order submission and immediate execution lifecycle.
 * Lifecycle: SUBMITTED -> ACCEPTED or REJECTED -> FILLED.
 * All orders and executions are persisted for audit retention.
 */
@Service
public class OrderSubmissionService {

    private final AccountClient accountClient;
    private final InstrumentRepository instrumentRepository;
    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final FillRecorder fillRecorder;
    private final TradeValidationService tradeValidationService;
    private final CurrentQuoteService currentQuoteService;

    /**
     * Constructs an instance of OrderSubmissionService with the specified dependencies.
     * @param accountClient the client used for account-related operations
     * @param instrumentRepository the repository for accessing instrument data
     * @param orderRepository the repository for persisting order data
     * @param executionRepository the repository for persisting execution data
     * @param fillRecorder the component responsible for recording fill details
     * @param tradeValidationService the service used for validating trades
     * @param currentQuoteService the service used for obtaining current market quotes
     */
    public OrderSubmissionService(AccountClient accountClient,
                                  InstrumentRepository instrumentRepository,
                                  OrderRepository orderRepository,
                                  ExecutionRepository executionRepository,
                                  FillRecorder fillRecorder,
                                  TradeValidationService tradeValidationService,
                                  CurrentQuoteService currentQuoteService) {
        this.accountClient = accountClient;
        this.instrumentRepository = instrumentRepository;
        this.orderRepository = orderRepository;
        this.executionRepository = executionRepository;
        this.fillRecorder = fillRecorder;
        this.tradeValidationService = tradeValidationService;
        this.currentQuoteService = currentQuoteService;
    }

    /**
     * Submits an order and triggers immediate execution processing.
     *
     * @param request the order submission request
     * @return OrderSubmissionResponse containing order, execution, and balance details
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderSubmissionResponse submitOrder(OrderSubmissionRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order request body is required");
        }
        if (request.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be at least 1 whole share");
        }

        // 2. Resolve instrument
        Instrument instrument = resolveInstrument(request);

        // 2. Pre-trade check via AccountClient
        AccountValidationDto accountValidation;
        try {
            accountValidation = accountClient.getValidationData(request.accountId(), instrument.getInstrumentId());
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to validate account: " + ex.getMessage());
        }

        // 3. Store order immediately with status SUBMITTED for audit retention
        OffsetDateTime now = OffsetDateTime.now();
        Order order = new Order(request.accountId(), instrument, request.side(), request.quantity(), now);
        order.setAccountNumber(accountValidation.accountNumber());
        order = orderRepository.saveAndFlush(order);

        // 4. Resolve execution price (bid for SELL, ask for BUY from CurrentQuoteService)
        BigDecimal executionPrice;
        try {
            executionPrice = resolvePrice(request, instrument);
        } catch (QuoteUnavailableException | StaleQuoteException | ResponseStatusException ex) {
            String rejectReason = ex instanceof ResponseStatusException rse ? rse.getReason() : ex.getMessage();
            return rejectOrder(order, accountValidation, rejectReason != null ? rejectReason : "Market quote unavailable");
        }

        // 5. Execute Trade Validation
        TradeValidationResult validationResult = tradeValidationService.validateTrade(
                accountValidation, instrument, request.side(), request.quantity(), executionPrice);

        if (!validationResult.isValid()) {
            return rejectOrder(order, accountValidation, validationResult.reason());
        }

        // Acceptance flow: transition order to ACCEPTED
        OffsetDateTime acceptTime = OffsetDateTime.now();
        order.markAccepted(acceptTime);
        order = orderRepository.saveAndFlush(order);

        // 6. Execution success: record the FILLED execution, Cash Ledger, Position Ledger, Positions
        OffsetDateTime fillTime = OffsetDateTime.now();
        Execution execution = fillRecorder.recordFill(order, executionPrice, fillTime);

        // Transition order to FILLED
        order.markFilled(fillTime);
        order = orderRepository.saveAndFlush(order);

        BigDecimal balanceAfter = fillRecorder.getLastBalanceAfter();
        return toResponse(order, execution, balanceAfter);
    }

    /**
     * Rejects the specified order with the given reason and returns the corresponding response.
     * @param order the order to be rejected
     * @param accountValidation the account validation details associated with the order
     * @param reason the reason for rejecting the order
     * @return the response containing the result of the order rejection
     */
    private OrderSubmissionResponse rejectOrder(Order order, AccountValidationDto accountValidation, String reason) {
        OffsetDateTime rejectTime = OffsetDateTime.now();
        order.markRejected(reason, rejectTime);
        order = orderRepository.saveAndFlush(order);

        Execution execution = new Execution(
                order, null, null, Execution.Status.REJECTED, reason, rejectTime);
        execution = executionRepository.saveAndFlush(execution);

        BigDecimal currentBalance = accountValidation != null ? accountValidation.cashBalance() : BigDecimal.ZERO;
        return toResponse(order, execution, currentBalance);
    }

    /**
     * Returns the instrument associated with the order submission request given Id, or symbol if Id is not provided.
     * @param request the order submission request containing instrument details
     * @return the resolved instrument
     * @throws ResponseStatusException not found (404) if the instrument cannot be resolved by ID or symbol
     * @throws ResponseStatusException bad request (400) if neither instrumentId nor symbol is provided.
     */
    private Instrument resolveInstrument(OrderSubmissionRequest request) {
        if (request.instrumentId() != null) {
            return instrumentRepository.findById(request.instrumentId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Instrument not found"));
        }
        if (request.symbol() != null && !request.symbol().isBlank()) {
            return instrumentRepository.findBySymbol(request.symbol().trim().toUpperCase())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Instrument not found for symbol: " + request.symbol()));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Either instrumentId or symbol must be provided");
    }

    /**
     * Resolves the price for the order submission request based on the provided instrument and current market quotes.
     * @param request the order submission request containing price and side details
     * @param instrument the instrument associated with the order
     * @return the resolved price for the order
     * @throws ResponseStatusException unprocessable entity (422) if no valid price can be determined
     */
    private BigDecimal resolvePrice(OrderSubmissionRequest request, Instrument instrument) {
        if (request.price() != null && request.price().compareTo(BigDecimal.ZERO) > 0) {
            return request.price().setScale(4, RoundingMode.HALF_UP);
        }

        QuoteSnapshot quote = currentQuoteService.getCurrentQuote(instrument.getSymbol());
        if (quote == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No quote returned for symbol: " + instrument.getSymbol());
        }

        BigDecimal price;
        if (request.side() == Order.Side.BUY) {
            price = (quote.askPrice() != null && quote.askPrice().compareTo(BigDecimal.ZERO) > 0)
                    ? quote.askPrice()
                    : quote.lastPrice();
        } else {
            price = (quote.bidPrice() != null && quote.bidPrice().compareTo(BigDecimal.ZERO) > 0)
                    ? quote.bidPrice()
                    : quote.lastPrice();
        }

        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No valid executable price found for symbol: " + instrument.getSymbol());
        }

        return price.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Converts the given order and execution details into an OrderSubmissionResponse.
     * @param order the order entity containing order details
     * @param execution the execution entity containing execution details
     * @param currentBalance the current account balance after the order execution
     * @return an OrderSubmissionResponse representing the order and execution details
     */
    private OrderSubmissionResponse toResponse(Order order, Execution execution, BigDecimal currentBalance) {
        OrderSubmissionResponse.ExecutionDto execDto = new OrderSubmissionResponse.ExecutionDto(
                execution.getExecutionId(),
                execution.getFillQuantity(),
                execution.getFillPrice(),
                execution.getStatus().name(),
                execution.getExecutedAt(),
                execution.getReason()
        );

        return new OrderSubmissionResponse(
                order.getOrderId(),
                order.getAccountId(),
                order.getAccountNumber(),
                order.getInstrument().getInstrumentId(),
                order.getInstrument().getSymbol(),
                order.getSide().name(),
                order.getQuantity(),
                order.getStatus().name(),
                order.getSubmittedAt(),
                order.getAcceptedAt(),
                order.getRejectedAt(),
                order.getFilledAt(),
                order.getRejectionReason(),
                execDto,
                currentBalance
        );
    }
}
