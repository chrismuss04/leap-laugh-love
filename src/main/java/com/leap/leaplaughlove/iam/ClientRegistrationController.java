package com.leap.leaplaughlove.iam;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** PB-02: receives client registration submissions and persists them (iam.clients + iam.client_profile). */
@RestController
@RequestMapping("/api/v1/clients")
public class ClientRegistrationController {

    private final ClientRepository clientRepository;

    public ClientRegistrationController(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest request) {
        if (clientRepository.existsByEmail(request.email())) {
            throw new DuplicateClientException("email already registered");
        }
        if (clientRepository.existsBySsn(request.ssn())) {
            throw new DuplicateClientException("ssn already registered");
        }

        OffsetDateTime now = OffsetDateTime.now();
        Client client = new Client(
                UUID.randomUUID(), request.email(), request.phone(), "PENDING", now,
                request.fullName(), request.dateOfBirth(), request.ssn(),
                request.addressLine1(), request.addressLine2(), request.city(),
                request.stateRegion(), request.postalCode(), request.countryCode(),
                request.experienceLevel(), request.initialDepositAmount());
        clientRepository.save(client);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegistrationResponse(client.getClientId(), client.getEmail(), client.getStatus()));
    }

    @ExceptionHandler(DuplicateClientException.class)
    public ResponseEntity<String> handleDuplicate(DuplicateClientException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<String> handleDataIntegrity(org.springframework.dao.DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body("email or ssn already registered");
    }

    public record RegistrationRequest(
            @NotBlank @Email String email,
            String phone,
            @NotBlank String fullName,
            @NotNull @Past LocalDate dateOfBirth,
            @NotBlank @Pattern(regexp = "\\d{3}-\\d{2}-\\d{4}", message = "ssn must be in format XXX-XX-XXXX") String ssn,
            @NotBlank String addressLine1,
            String addressLine2,
            @NotBlank String city,
            String stateRegion,
            @NotBlank String postalCode,
            @NotBlank @Size(min = 2, max = 2) String countryCode,
            @NotBlank @Pattern(regexp = "NOVICE|INTERMEDIATE|ADVANCED") String experienceLevel,
            @NotNull @DecimalMin("0.00") BigDecimal initialDepositAmount) {
    }

    public record RegistrationResponse(UUID clientId, String email, String status) {
    }

    static class DuplicateClientException extends RuntimeException {
        DuplicateClientException(String message) {
            super(message);
        }
    }
}
