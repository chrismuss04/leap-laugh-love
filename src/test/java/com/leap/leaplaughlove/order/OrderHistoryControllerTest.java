package com.leap.leaplaughlove.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Security filters are disabled here because clientId auth resolution isn't in scope for this story yet.
@WebMvcTest(OrderHistoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrderHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderHistoryService orderHistoryService;

    @Test
    void returnsOkWithEmptyContentForClientWithNoOrders() throws Exception {
        UUID clientId = UUID.randomUUID();
        when(orderHistoryService.getOrderHistory(eq(clientId), any(int.class), any(int.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/orders/history").param("clientId", clientId.toString()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"content\":[],\"totalElements\":0}", false));
    }

    @Test
    void returnsBadRequestForInvalidPageSize() throws Exception {
        UUID clientId = UUID.randomUUID();
        when(orderHistoryService.getOrderHistory(any(UUID.class), any(int.class), any(int.class)))
                .thenThrow(new IllegalArgumentException("size must be between 1 and 100"));

        mockMvc.perform(get("/api/orders/history")
                        .param("clientId", clientId.toString())
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
    }
}
