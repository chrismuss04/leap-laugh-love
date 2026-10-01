package com.leap.leaplaughlove.iam.client;

import com.leap.leaplaughlove.common.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Controller exposing the authenticated client's own profile, so the UI can show who is signed
 * in without the login response or the JWT having to carry profile fields, and letting the client
 * update it. Maps to the /api/iam/v1/clients/me endpoint.
 */
@RestController
@RequestMapping("/api/iam/v1/clients")
public class ClientProfileController {

    private final ClientRepository clientRepository;
    private final ClientCredentialsRepository credentialsRepository;
    private final PasswordEncoder passwordEncoder;

    /** Constructs a new ClientProfileController with the specified dependencies. */
    public ClientProfileController(ClientRepository clientRepository,
                                   ClientCredentialsRepository credentialsRepository,
                                   PasswordEncoder passwordEncoder) {
        this.clientRepository = clientRepository;
        this.credentialsRepository = credentialsRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Retrieves the profile of the authenticated client. Sensitive identity fields (SSN, date of
     * birth) are deliberately left out - this is display data for the signed-in header, the
     * profile page and account settings.
     * @return the authenticated client's display profile
     * @throws ResponseStatusException with 404 NOT_FOUND if the token's client no longer exists
     */
    @GetMapping("/me")
    public ClientProfileResponse me() {
        return toResponse(authenticatedClient());
    }

    /**
     * Updates the authenticated client's settings. Changing the email or password needs the current
     * password (403 if wrong); a new email already in use is a 409.
     */
    @PutMapping("/me")
    @Transactional
    public ClientProfileResponse updateMe(@Valid @RequestBody UpdateSettingsRequest request) {
        Client client = authenticatedClient();
        boolean emailChanged = !client.getEmail().equals(request.email());
        if (emailChanged || request.newPassword() != null) {
            ClientCredentials credentials = credentialsRepository.findByClientId(client.getClientId())
                    .filter(c -> request.currentPassword() != null
                            && passwordEncoder.matches(request.currentPassword(), c.getPasswordHash()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "current password is incorrect"));
            if (emailChanged && clientRepository.existsByEmail(request.email())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
            }
            if (request.newPassword() != null) {
                credentials.setPasswordHash(passwordEncoder.encode(request.newPassword()));
            }
        }
        client.updateSettings(request.fullName(), request.email(),
                ClientRegistrationController.normalizePhone(request.phone()),
                request.notifyOrderFills(), request.notifyPriceAlerts());
        client.updateAddress(request.addressLine1(), request.addressLine2(), request.city(),
                request.stateRegion(), request.postalCode(), request.countryCode());
        return toResponse(client);
    }

    private Client authenticatedClient() {
        UUID clientId = SecurityUtils.getAuthenticatedClientId();
        return clientRepository.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found"));
    }

    private static ClientProfileResponse toResponse(Client client) {
        return new ClientProfileResponse(client.getClientId(), client.getEmail(), client.getFullName(),
                client.getExperienceLevel(), client.getStatus(), client.getCreatedAt(), client.getPhone(),
                client.getAddressLine1(), client.getAddressLine2(), client.getCity(), client.getStateRegion(),
                client.getPostalCode(), client.getCountryCode(),
                client.isNotifyOrderFills(), client.isNotifyPriceAlerts());
    }

    /** The client's display profile; leaves out SSN and date of birth. */
    public record ClientProfileResponse(UUID clientId, String email, String fullName,
                                        String experienceLevel, String status, OffsetDateTime createdAt,
                                        String phone, String addressLine1, String addressLine2, String city,
                                        String stateRegion, String postalCode, String countryCode,
                                        boolean notifyOrderFills, boolean notifyPriceAlerts) {
    }

    /** The settings a client can change; currentPassword is needed to change email or password. */
    public record UpdateSettingsRequest(
            @NotBlank String fullName,
            @NotBlank @Email String email,
            String phone,
            @NotBlank String addressLine1,
            String addressLine2,
            @NotBlank String city,
            String stateRegion,
            @NotBlank String postalCode,
            @NotBlank @Size(min = 2, max = 2) String countryCode,
            boolean notifyOrderFills,
            boolean notifyPriceAlerts,
            String currentPassword,
            @Size(min = 8, message = "password must be at least 8 characters") String newPassword) {
    }
}
