package com.leap.leaplaughlove.iam.client;

import com.leap.leaplaughlove.iam.account.ClientRegisteredEvent;
import com.leap.leaplaughlove.iam.events.ClientRegistrationEvent;
import com.leap.leaplaughlove.iam.staff.StaffCredentialsRepository;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ClientRegistrationController unit tests")
class ClientRegistrationControllerUnitTest {

    private final ClientRepository clients = mock(ClientRepository.class);
    private final ClientCredentialsRepository credentials = mock(ClientCredentialsRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final StaffCredentialsRepository staff = mock(StaffCredentialsRepository.class);
    private final ClientRegistrationController controller =
            new ClientRegistrationController(clients, credentials, encoder, events, staff);

    private ClientRegistrationController.RegistrationRequest request() {
        return new ClientRegistrationController.RegistrationRequest(
                "ada@example.com", "5551234567", "Ada Lovelace", LocalDate.of(1990, 1, 1), "123-45-6789",
                "1 Main St", null, "London", null, "N1", "GB", "INTERMEDIATE", new BigDecimal("5000.00"),
                "password1");
    }

    @Test
    @DisplayName("formats a ten-digit phone number, strips other numbers to digits, and keeps null")
    void normalizesPhones() {
        assertEquals("(555) 123-4567", ClientRegistrationController.normalizePhone("555.123.4567"));
        assertEquals("15551234567", ClientRegistrationController.normalizePhone("+1 (555) 123-4567"));
        assertEquals("", ClientRegistrationController.normalizePhone("n/a"));
        assertNull(ClientRegistrationController.normalizePhone(null));
    }

    // Analyst login: sign-in checks clients first, so a client can't take a staff member's email.
    @Test
    @DisplayName("refuses an email that belongs to a staff member")
    void refusesStaffEmail() {
        when(staff.existsByEmail("ada@example.com")).thenReturn(true);

        var ex = assertThrows(ClientRegistrationController.DuplicateClientException.class,
                () -> controller.register(request()));

        assertEquals("email already registered", ex.getMessage());
        verify(clients, never()).save(any());
    }

    @Test
    @DisplayName("refuses a duplicate email before touching anything else")
    void rejectsDuplicateEmail() {
        when(clients.existsByEmail("ada@example.com")).thenReturn(true);

        ClientRegistrationController.DuplicateClientException ex = assertThrows(
                ClientRegistrationController.DuplicateClientException.class, () -> controller.register(request()));

        assertEquals("email already registered", ex.getMessage());
        verify(clients, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("refuses a duplicate SSN")
    void rejectsDuplicateSsn() {
        when(clients.existsBySsn("123-45-6789")).thenReturn(true);

        ClientRegistrationController.DuplicateClientException ex = assertThrows(
                ClientRegistrationController.DuplicateClientException.class, () -> controller.register(request()));

        assertEquals("ssn already registered", ex.getMessage());
        verify(clients, never()).save(any());
    }

    // Verify registration preserves provisioning and reports the saved client's ID and timestamp.
    @Test
    @DisplayName("registers an active client with hashed credentials and announces it")
    void registersClient() {
        when(encoder.encode("password1")).thenReturn("hashed");

        ResponseEntity<ClientRegistrationController.RegistrationResponse> response = controller.register(request());

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("ada@example.com", response.getBody().email());
        assertEquals("ACTIVE", response.getBody().status());
        verify(clients).save(any(Client.class));
        verify(credentials).save(any(ClientCredentials.class));
        verify(events).publishEvent(any(ClientRegisteredEvent.class));
        var saved = ArgumentCaptor.forClass(Client.class);
        verify(clients).save(saved.capture());
        verify(events).publishEvent(new ClientRegistrationEvent(saved.getValue().getClientId(),
                saved.getValue().getCreatedAt()));
    }

    @Test
    @DisplayName("maps duplicates, whether caught up front or by the database, to a 409")
    void mapsConflicts() {
        ResponseEntity<String> duplicate = controller.handleDuplicate(
                new ClientRegistrationController.DuplicateClientException("email already registered"));
        ResponseEntity<String> integrity = controller.handleDataIntegrity(new DataIntegrityViolationException("dup"));

        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        assertEquals("email already registered", duplicate.getBody());
        assertEquals(HttpStatus.CONFLICT, integrity.getStatusCode());
        assertEquals("email or ssn already registered", integrity.getBody());
    }
}
