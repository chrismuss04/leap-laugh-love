package com.leap.leaplaughlove.iam.client;

import com.leap.leaplaughlove.iam.account.ClientRegisteredEvent;
import com.leap.leaplaughlove.iam.client.ClientRegistrationController.RegistrationRequest;
import com.leap.leaplaughlove.iam.client.RegistrationEmailEvent.Detail;
import com.leap.leaplaughlove.iam.client.RegistrationEmailEvent.Kind;
import com.leap.leaplaughlove.iam.events.ClientRegistrationEvent;
import com.leap.leaplaughlove.iam.staff.StaffCredentialsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service for client registration: takes an application, and opens the client once the applicant
 * has confirmed their email address with the link they were sent.
 *
 * An email address, phone number and SSN each belong to one client. Whether an application's
 * details are already registered is never shown to whoever submitted it; it is only ever emailed.
 */
@Service
public class ClientRegistrationService {

    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final int TOKEN_BYTES = 32;

    private final ClientRepository clientRepository;
    private final ClientCredentialsRepository credentialsRepository;
    private final PendingRegistrationRepository pendingRepository;
    // Analyst login: sign-in looks clients up before staff, so a client with a staff member's
    // email would lock that staff member out of reporting.
    private final StaffCredentialsRepository staffCredentialsRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration tokenTtl;
    private final String linkBaseUrl;
    private final SecureRandom random = new SecureRandom();

    /**
     * Constructs a new ClientRegistrationService with the specified dependencies.
     * @param clientRepository the repository for managing client entities
     * @param credentialsRepository the repository for managing client credentials
     * @param pendingRepository the repository storing applications until their email is confirmed
     * @param staffCredentialsRepository the staff sign-in credentials, whose emails clients can't register with
     * @param passwordEncoder the encoder used to hash the applicant's password before it is stored
     * @param eventPublisher publishes the emails to send, and the registration once a client is opened
     * @param tokenTtlMinutes how long an emailed link stays usable, in minutes
     * @param linkBaseUrl the frontend page the link opens; the token is appended as a query parameter
     */
    public ClientRegistrationService(ClientRepository clientRepository,
                                     ClientCredentialsRepository credentialsRepository,
                                     PendingRegistrationRepository pendingRepository,
                                     StaffCredentialsRepository staffCredentialsRepository,
                                     PasswordEncoder passwordEncoder,
                                     ApplicationEventPublisher eventPublisher,
                                     @Value("${app.registration.token-ttl-minutes}") long tokenTtlMinutes,
                                     @Value("${app.registration.link-base-url}") String linkBaseUrl) {
        if (tokenTtlMinutes <= 0) {
            throw new IllegalArgumentException("app.registration.token-ttl-minutes must be > 0");
        }
        if (linkBaseUrl == null || linkBaseUrl.isBlank()) {
            throw new IllegalArgumentException("app.registration.link-base-url must not be blank");
        }
        this.clientRepository = clientRepository;
        this.credentialsRepository = credentialsRepository;
        this.pendingRepository = pendingRepository;
        this.staffCredentialsRepository = staffCredentialsRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.tokenTtl = Duration.ofMinutes(tokenTtlMinutes);
        this.linkBaseUrl = linkBaseUrl;
    }

    /**
     * Takes an application. If its email, phone number and SSN are all unused it is stored and the
     * applicant is emailed a link that opens the account; otherwise nothing is stored and they are
     * emailed that instead. The caller is never told which, so this cannot be used to find out
     * which emails, phone numbers or SSNs are registered.
     * @param request the application
     */
    @Transactional
    public void submit(RegistrationRequest request) {
        Instant now = Instant.now();
        String phone = ClientRegistrationController.normalizePhone(request.phone());
        // Hashed before anything is looked up: hashing is slow on purpose, so skipping it for a
        // refused application would show in how long the answer took.
        String passwordHash = passwordEncoder.encode(request.password());
        pendingRepository.deleteExpired(now);

        // Each account whose email address, phone number or SSN was used, with everything of theirs
        // that was, so each gets one email listing all of it. Only they are told which details; an
        // applicant using someone else's never is.
        boolean emailTaken = emailTaken(request.email());
        Map<String, Set<Detail>> owners = new LinkedHashMap<>();
        if (emailTaken) {
            owners.computeIfAbsent(request.email(), key -> EnumSet.noneOf(Detail.class)).add(Detail.EMAIL);
        }
        clientRepository.findFirstBySsn(request.ssn()).map(Client::getEmail).ifPresent(owner ->
                owners.computeIfAbsent(owner, key -> EnumSet.noneOf(Detail.class)).add(Detail.SSN));
        if (phone != null) {
            clientRepository.findFirstByPhone(phone).map(Client::getEmail).ifPresent(owner ->
                    owners.computeIfAbsent(owner, key -> EnumSet.noneOf(Detail.class)).add(Detail.PHONE));
        }
        if (!owners.isEmpty()) {
            // An applicant whose own address is registered hears it in that account's email instead.
            if (!emailTaken) {
                eventPublisher.publishEvent(RegistrationEmailEvent.of(Kind.NOT_COMPLETED, request.email()));
            }
            owners.forEach((owner, details) -> eventPublisher.publishEvent(
                    details.equals(EnumSet.of(Detail.EMAIL))
                            ? RegistrationEmailEvent.of(Kind.ALREADY_REGISTERED, owner)
                            : RegistrationEmailEvent.detailsReused(owner, details)));
            return;
        }

        String token = newToken();
        Instant expiresAt = now.plus(tokenTtl);
        pendingRepository.replace(new PendingRegistration(
                UUID.randomUUID(), hash(token), request.email(), phone,
                request.fullName(), request.dateOfBirth(), request.ssn(),
                request.addressLine1(), request.addressLine2(), request.city(),
                request.stateRegion(), request.postalCode(), request.countryCode(),
                request.experienceLevel(), request.initialDepositAmount(), passwordHash, now, expiresAt));
        eventPublisher.publishEvent(RegistrationEmailEvent.verify(
                request.email(), linkBaseUrl + "?token=" + token, expiresAt));
    }

    /**
     * Opens the client an application was for, using the token from the emailed link. The details
     * are checked again, because another application for the same phone number or SSN may have
     * been confirmed since this one was submitted.
     * @param token the token from the emailed link
     * @throws InvalidVerificationLinkException if the token is unknown, expired or already used, or
     *         the application's details now belong to another client
     */
    @Transactional
    public void verify(String token) {
        PendingRegistration pending = pendingRepository.consume(hash(token), Instant.now())
                .orElseThrow(InvalidVerificationLinkException::new);
        if (emailTaken(pending.email())
                || clientRepository.existsBySsn(pending.ssn())
                || (pending.phone() != null && clientRepository.existsByPhone(pending.phone()))) {
            throw new InvalidVerificationLinkException();
        }

        Client client = new Client(
                UUID.randomUUID(), pending.email(), pending.phone(), ACTIVE_STATUS, OffsetDateTime.now(),
                pending.fullName(), pending.dateOfBirth(), pending.ssn(),
                pending.addressLine1(), pending.addressLine2(), pending.city(),
                pending.stateRegion(), pending.postalCode(), pending.countryCode(),
                pending.experienceLevel(), pending.initialDepositAmount());
        try {
            // Flushed here so that losing a race for the email, phone number or SSN is refused
            // like any other dead link, rather than failing when the transaction commits.
            clientRepository.saveAndFlush(client);
        } catch (DataIntegrityViolationException e) {
            throw new InvalidVerificationLinkException();
        }
        credentialsRepository.save(new ClientCredentials(client.getClientId(), pending.passwordHash(), 0, null));

        eventPublisher.publishEvent(new ClientRegistrationEvent(client.getClientId(), client.getCreatedAt()));

        eventPublisher.publishEvent(new ClientRegisteredEvent(
                client.getClientId(), client.getEmail(), pending.initialDepositAmount()));
    }

    private boolean emailTaken(String email) {
        return clientRepository.existsByEmail(email) || staffCredentialsRepository.existsByEmail(email);
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // The token is long and random, so a fast unsalted hash is enough to keep the stored value unusable.
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
