package com.leap.leaplaughlove.account.balance;

import com.leap.leaplaughlove.account.common.AccountGlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransferController Unit Tests")
class TransferControllerTest {

    private static final String URL = "/api/account/balance/transfers";

    @Mock private TransferService transferService;

    private MockMvc mockMvc;
    private final UUID fromId = UUID.randomUUID();
    private final UUID toId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TransferController(transferService))
                .setControllerAdvice(new AccountGlobalExceptionHandler())
                .build();
    }

    private String body(String amount) {
        return "{\"fromAccountId\":\"" + fromId + "\",\"toAccountId\":\"" + toId + "\",\"amount\":" + amount
                + ",\"description\":\"note\"}";
    }

    @Test
    @DisplayName("POST returns the transfer with both balances after the move")
    void transfer_returnsBothBalances() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferService.transfer(new CashTransferRequest(fromId, toId, new BigDecimal("25.00"), "note")))
                .thenReturn(new CashTransferResponse(transferId, fromId, toId, new BigDecimal("25.00"), "USD",
                        new BigDecimal("75.00"), new BigDecimal("125.00"), OffsetDateTime.now()));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("25.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId", is(transferId.toString())))
                .andExpect(jsonPath("$.fromAccountId", is(fromId.toString())))
                .andExpect(jsonPath("$.toAccountId", is(toId.toString())))
                .andExpect(jsonPath("$.currency", is("USD")))
                .andExpect(jsonPath("$.fromBalanceAfter", is(75.00)))
                .andExpect(jsonPath("$.toBalanceAfter", is(125.00)));
    }

    @Test
    @DisplayName("POST with an amount below 0.01 is rejected by validation before reaching the service")
    void transfer_rejectsInvalidAmount() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("0")))
                .andExpect(status().is4xxClientError());

        verify(transferService, never()).transfer(any());
    }

    @Test
    @DisplayName("POST without a destination account is rejected by validation")
    void transfer_rejectsMissingAccount() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"" + fromId + "\",\"amount\":10.00}"))
                .andExpect(status().is4xxClientError());

        verify(transferService, never()).transfer(any());
    }

    @Test
    @DisplayName("POST surfaces a service rejection as a 400 with the message")
    void transfer_surfacesServiceRejection() throws Exception {
        when(transferService.transfer(any())).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Transfer amount cannot exceed available account balance"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("500.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Transfer amount cannot exceed available account balance")));
    }
}
