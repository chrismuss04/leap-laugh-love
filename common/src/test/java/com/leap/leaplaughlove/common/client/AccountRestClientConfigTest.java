package com.leap.leaplaughlove.common.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("AccountRestClientConfig Unit Tests")
class AccountRestClientConfigTest {

    @Test
    @DisplayName("configures the builder with the account-app base URL")
    void usesBaseUrl() {
        RestClient.Builder builder = RestClient.builder();

        RestClient client = new AccountRestClientConfig()
                .accountRestClient(builder, "http://account.test", 1500, 2500);

        // The bean keeps the builder's base URL; swap its request factory for a mock to check it.
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://account.test/ping"))
                .andRespond(withSuccess("pong", MediaType.TEXT_PLAIN));

        assertNotNull(client);
        assertEquals("pong", builder.build().get().uri("/ping").retrieve().body(String.class));
        server.verify();
    }
}
