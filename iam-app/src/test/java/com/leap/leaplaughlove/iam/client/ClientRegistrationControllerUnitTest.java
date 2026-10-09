package com.leap.leaplaughlove.iam.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("ClientRegistrationController unit tests")
class ClientRegistrationControllerUnitTest {

    private final ClientRegistrationService service = mock(ClientRegistrationService.class);
    private final ClientRegistrationController controller = new ClientRegistrationController(service);

    private ClientRegistrationController.RegistrationRequest request() {
        return new ClientRegistrationController.RegistrationRequest(
                "ada@example.com", "5551234567", "Ada Lovelace", LocalDate.of(1990, 1, 1), "123-45-6789",
                "1 Main St", null, "London", null, "N1", "GB", "INTERMEDIATE", new BigDecimal("5000.00"),
                "password1");
    }

    // Phone numbers are unique, so one number must not have two spellings.
    @Test
    @DisplayName("formats a US phone number with or without its country code, strips other numbers to digits")
    void normalizesPhones() {
        assertEquals("(555) 123-4567", ClientRegistrationController.normalizePhone("555.123.4567"));
        assertEquals("(555) 123-4567", ClientRegistrationController.normalizePhone("+1 (555) 123-4567"));
        assertEquals("442071234567", ClientRegistrationController.normalizePhone("+44 20 7123 4567"));
        assertNull(ClientRegistrationController.normalizePhone("n/a"));
        assertNull(ClientRegistrationController.normalizePhone(null));
    }

    @Test
    @DisplayName("hands the application to the service and tells the applicant to check their email")
    void acceptsAnApplication() {
        ResponseEntity<ClientRegistrationController.RegistrationResponse> response = controller.register(request());

        verify(service).submit(request());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(ClientRegistrationController.ACCEPTED_MESSAGE, response.getBody().message());
    }

    // Two applications for one email at the same moment: the loser must not be told it lost.
    @Test
    @DisplayName("answers a constraint violation exactly like a stored application")
    void answersARaceLikeAnyOtherApplication() {
        ResponseEntity<ClientRegistrationController.RegistrationResponse> stored = controller.register(request());
        ResponseEntity<ClientRegistrationController.RegistrationResponse> raced =
                controller.handleDataIntegrity(new DataIntegrityViolationException("dup"));

        assertEquals(stored.getStatusCode(), raced.getStatusCode());
        assertEquals(stored.getBody(), raced.getBody());
    }

    @Test
    @DisplayName("opens the account with the token from the emailed link")
    void verifiesALink() {
        ResponseEntity<Void> response = controller.verify(
                new ClientRegistrationController.VerifyRegistrationRequest("token"));

        verify(service).verify("token");
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }
}
