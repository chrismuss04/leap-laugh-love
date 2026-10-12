package com.leap.leaplaughlove.iam.client;

import com.leap.leaplaughlove.iam.account.ClientRegisteredEvent;
import com.leap.leaplaughlove.iam.client.ClientRegistrationController.RegistrationRequest;
import com.leap.leaplaughlove.iam.client.RegistrationEmailEvent.Detail;
import com.leap.leaplaughlove.iam.client.RegistrationEmailEvent.Kind;
import com.leap.leaplaughlove.iam.events.ClientRegistrationEvent;
import com.leap.leaplaughlove.iam.staff.StaffCredentialsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ClientRegistrationService unit tests")
class ClientRegistrationServiceTest {

    private static final String LINK_PREFIX = "http://localhost:4200/verify-email?token=";

    private final ClientRepository clients = mock(ClientRepository.class);
    private final ClientCredentialsRepository credentials = mock(ClientCredentialsRepository.class);
    private final PendingRegistrationRepository pendingRepository = mock(PendingRegistrationRepository.class);
    private final StaffCredentialsRepository staff = mock(StaffCredentialsRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ClientRegistrationService service = new ClientRegistrationService(
            clients, credentials, pendingRepository, staff, encoder, events, 60,
            "http://localhost:4200/verify-email");

    @BeforeEach
    void hashPasswords() {
        when(encoder.encode("password1")).thenReturn("hashed");
    }

    private RegistrationRequest request() {
        return new RegistrationRequest(
                "ada@example.com", "5551234567", "Ada Lovelace", LocalDate.of(1990, 1, 1), "123-45-6789",
                "1 Main St", null, "London", null, "N1", "GB", "INTERMEDIATE", new BigDecimal("5000.00"),
                "password1");
    }

    private PendingRegistration pending() {
        Instant now = Instant.now();
        return new PendingRegistration(UUID.randomUUID(), "hash", "ada@example.com", "(555) 123-4567",
                "Ada Lovelace", LocalDate.of(1990, 1, 1), "123-45-6789", "1 Main St", null, "London", null,
                "N1", "GB", "INTERMEDIATE", new BigDecimal("5000.00"), "hashed", now, now.plusSeconds(3600));
    }

    private Client existingClient(String email) {
        Client client = mock(Client.class);
        when(client.getEmail()).thenReturn(email);
        return client;
    }

    private List<Object> published() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("stores an unused application and emails a link whose token is only kept as a hash")
    void storesAnApplicationAndEmailsItsLink() {
        service.submit(request());

        ArgumentCaptor<PendingRegistration> stored = ArgumentCaptor.forClass(PendingRegistration.class);
        verify(pendingRepository).replace(stored.capture());
        assertEquals("ada@example.com", stored.getValue().email());
        assertEquals("(555) 123-4567", stored.getValue().phone());
        assertEquals("hashed", stored.getValue().passwordHash());

        assertThat(published()).hasSize(1);
        RegistrationEmailEvent email = (RegistrationEmailEvent) published().get(0);
        assertEquals(Kind.VERIFY, email.kind());
        assertEquals("ada@example.com", email.to());
        assertEquals(stored.getValue().expiresAt(), email.expiresAt());
        assertThat(email.link()).startsWith(LINK_PREFIX);
        assertThat(email.link()).doesNotContain(stored.getValue().tokenHash());
        // Nothing is a client yet: the account opens when the link is used.
        verify(clients, never()).save(any());
        verify(clients, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("stores nothing for a registered email and tells its owner")
    void emailsTheOwnerOfARegisteredEmail() {
        when(clients.existsByEmail("ada@example.com")).thenReturn(true);

        service.submit(request());

        verify(pendingRepository, never()).replace(any());
        assertThat(published()).containsExactly(
                RegistrationEmailEvent.of(Kind.ALREADY_REGISTERED, "ada@example.com"));
    }

    // Analyst login: sign-in checks clients first, so a client can't take a staff member's email.
    @Test
    @DisplayName("stores nothing for an email that belongs to a staff member")
    void refusesStaffEmail() {
        when(staff.existsByEmail("ada@example.com")).thenReturn(true);

        service.submit(request());

        verify(pendingRepository, never()).replace(any());
        assertThat(published()).containsExactly(
                RegistrationEmailEvent.of(Kind.ALREADY_REGISTERED, "ada@example.com"));
    }

    @Test
    @DisplayName("stores nothing for a registered SSN, and tells its owner it was their SSN but not the applicant")
    void refusesARegisteredSsn() {
        Client owner = existingClient("owner@example.com");
        when(clients.findFirstBySsn("123-45-6789")).thenReturn(Optional.of(owner));

        service.submit(request());

        verify(pendingRepository, never()).replace(any());
        assertThat(published()).containsExactly(
                RegistrationEmailEvent.of(Kind.NOT_COMPLETED, "ada@example.com"),
                RegistrationEmailEvent.detailsReused("owner@example.com", Set.of(Detail.SSN)));
    }

    @Test
    @DisplayName("stores nothing for a registered phone number, however it was typed")
    void refusesARegisteredPhone() {
        Client owner = existingClient("owner@example.com");
        when(clients.findFirstByPhone("(555) 123-4567")).thenReturn(Optional.of(owner));

        service.submit(request());

        verify(pendingRepository, never()).replace(any());
        assertThat(published()).containsExactly(
                RegistrationEmailEvent.of(Kind.NOT_COMPLETED, "ada@example.com"),
                RegistrationEmailEvent.detailsReused("owner@example.com", Set.of(Detail.PHONE)));
    }

    // One email, listing everything of theirs that was used; the applicant's address is theirs too.
    @Test
    @DisplayName("tells a client their email address, phone number and SSN were all used, in one email")
    void listsEveryDetailOfOneClient() {
        when(clients.existsByEmail("ada@example.com")).thenReturn(true);
        Client owner = existingClient("ada@example.com");
        when(clients.findFirstBySsn("123-45-6789")).thenReturn(Optional.of(owner));
        when(clients.findFirstByPhone("(555) 123-4567")).thenReturn(Optional.of(owner));

        service.submit(request());

        verify(pendingRepository, never()).replace(any());
        assertThat(published()).containsExactly(RegistrationEmailEvent.detailsReused(
                "ada@example.com", Set.of(Detail.EMAIL, Detail.PHONE, Detail.SSN)));
    }

    @Test
    @DisplayName("tells both clients when the email address is one client's and the SSN another's")
    void tellsTheEmailOwnerAndTheSsnOwner() {
        when(clients.existsByEmail("ada@example.com")).thenReturn(true);
        Client ssnOwner = existingClient("ssn.owner@example.com");
        when(clients.findFirstBySsn("123-45-6789")).thenReturn(Optional.of(ssnOwner));

        service.submit(request());

        assertThat(published()).containsExactly(
                RegistrationEmailEvent.of(Kind.ALREADY_REGISTERED, "ada@example.com"),
                RegistrationEmailEvent.detailsReused("ssn.owner@example.com", Set.of(Detail.SSN)));
    }

    @Test
    @DisplayName("tells each client only about their own detail when the SSN and phone number are two clients'")
    void tellsEachOwnerOnlyTheirOwnDetail() {
        Client ssnOwner = existingClient("ssn.owner@example.com");
        Client phoneOwner = existingClient("phone.owner@example.com");
        when(clients.findFirstBySsn("123-45-6789")).thenReturn(Optional.of(ssnOwner));
        when(clients.findFirstByPhone("(555) 123-4567")).thenReturn(Optional.of(phoneOwner));

        service.submit(request());

        assertThat(published()).containsExactly(
                RegistrationEmailEvent.of(Kind.NOT_COMPLETED, "ada@example.com"),
                RegistrationEmailEvent.detailsReused("ssn.owner@example.com", Set.of(Detail.SSN)),
                RegistrationEmailEvent.detailsReused("phone.owner@example.com", Set.of(Detail.PHONE)));
    }

    @Test
    @DisplayName("emails one client once, naming both, when the phone number and the SSN are theirs")
    void emailsAnOwnerOnce() {
        Client owner = existingClient("owner@example.com");
        when(clients.findFirstBySsn("123-45-6789")).thenReturn(Optional.of(owner));
        when(clients.findFirstByPhone("(555) 123-4567")).thenReturn(Optional.of(owner));

        service.submit(request());

        assertThat(published()).containsExactly(
                RegistrationEmailEvent.of(Kind.NOT_COMPLETED, "ada@example.com"),
                RegistrationEmailEvent.detailsReused("owner@example.com", Set.of(Detail.SSN, Detail.PHONE)));
    }

    // Hashing is the slow step, so skipping it for a refused application would show in the timing.
    @Test
    @DisplayName("hashes the password whether or not the application is stored")
    void hashesThePasswordOnEveryPath() {
        when(clients.existsByEmail("ada@example.com")).thenReturn(true);

        service.submit(request());

        verify(encoder).encode("password1");
    }

    @Test
    @DisplayName("opens an active client with the stored password hash and announces it")
    void opensTheClientWhenTheLinkIsUsed() {
        when(pendingRepository.consume(anyString(), any())).thenReturn(Optional.of(pending()));

        service.verify("token");

        ArgumentCaptor<Client> saved = ArgumentCaptor.forClass(Client.class);
        verify(clients).saveAndFlush(saved.capture());
        assertEquals("ada@example.com", saved.getValue().getEmail());
        assertEquals("(555) 123-4567", saved.getValue().getPhone());
        assertEquals("ACTIVE", saved.getValue().getStatus());
        ArgumentCaptor<ClientCredentials> savedCredentials = ArgumentCaptor.forClass(ClientCredentials.class);
        verify(credentials).save(savedCredentials.capture());
        assertEquals("hashed", savedCredentials.getValue().getPasswordHash());
        assertThat(published()).containsExactly(
                new ClientRegistrationEvent(saved.getValue().getClientId(), saved.getValue().getCreatedAt()),
                new ClientRegisteredEvent(saved.getValue().getClientId(), "ada@example.com",
                        new BigDecimal("5000.00")));
    }

    @Test
    @DisplayName("refuses a link that is unknown, expired or already used")
    void refusesADeadLink() {
        when(pendingRepository.consume(anyString(), any())).thenReturn(Optional.empty());

        assertThrows(InvalidVerificationLinkException.class, () -> service.verify("token"));

        verify(clients, never()).saveAndFlush(any());
    }

    // Applications don't reserve anything, so two for one SSN can both be waiting; the first link used wins.
    @Test
    @DisplayName("refuses a link whose details were registered after it was sent")
    void refusesALinkWhoseDetailsAreNowTaken() {
        when(pendingRepository.consume(anyString(), any())).thenReturn(Optional.of(pending()));
        when(clients.existsBySsn("123-45-6789")).thenReturn(true);

        assertThrows(InvalidVerificationLinkException.class, () -> service.verify("token"));

        verify(clients, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("refuses a link that loses a race for its details at the database")
    void refusesALinkThatLosesARace() {
        when(pendingRepository.consume(anyString(), any())).thenReturn(Optional.of(pending()));
        when(clients.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

        assertThrows(InvalidVerificationLinkException.class, () -> service.verify("token"));

        verify(credentials, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("refuses to start without a link lifetime or a page for the link to open")
    void validatesItsConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ClientRegistrationService(
                clients, credentials, pendingRepository, staff, encoder, events, 0, "http://localhost"));
        assertThrows(IllegalArgumentException.class, () -> new ClientRegistrationService(
                clients, credentials, pendingRepository, staff, encoder, events, 60, " "));
    }
}
