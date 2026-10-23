package com.leap.leaplaughlove.iam.client;

import com.leap.leaplaughlove.common.security.SecurityUtils;
import com.leap.leaplaughlove.iam.staff.StaffCredentialsRepository;
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
    private final StaffCredentialsRepository staffCredentialsRepository;

    /**
     * Constructs a new ClientProfileController with the specified dependencies.
     * @param clientRepository reads and saves client profiles
     * @param credentialsRepository the clients' sign-in credentials
     * @param passwordEncoder hashes and checks passwords
     * @param staffCredentialsRepository staff sign-in credentials, so a client can't take a staff email
     */
    public ClientProfileController(ClientRepository clientRepository,
                                   ClientCredentialsRepository credentialsRepository,
                                   PasswordEncoder passwordEncoder,
                                   StaffCredentialsRepository staffCredentialsRepository) {
        this.clientRepository = clientRepository;
        this.credentialsRepository = credentialsRepository;
        this.passwordEncoder = passwordEncoder;
        this.staffCredentialsRepository = staffCredentialsRepository;
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
     * password (403 if wrong); a new email or phone number already in use is a 409 whose error
     * code (EMAIL_TAKEN or PHONE_TAKEN) says which, so the settings page can.
     * @param request the new settings
     * @return the updated profile
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
            // Analyst login: a staff member's email is as taken as another client's (see registration).
            if (emailChanged && (clientRepository.existsByEmail(request.email())
                    || staffCredentialsRepository.existsByEmail(request.email()))) {
                throw new DetailTakenException("EMAIL_TAKEN", "That email is already registered to another account.");
            }
            if (request.newPassword() != null) {
                credentials.setPasswordHash(passwordEncoder.encode(request.newPassword()));
            }
        }
        String phone = ClientRegistrationController.normalizePhone(request.phone());
        if (phone != null && clientRepository.existsByPhoneAndClientIdNot(phone, client.getClientId())) {
            throw new DetailTakenException("PHONE_TAKEN",
                    "That phone number is already registered to another account.");
        }
        client.updateSettings(request.fullName(), request.email(), phone,
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

    /**
     * The client's display profile; leaves out SSN and date of birth.
     * @param clientId the client's ID
     * @param email the client's email address
     * @param fullName the client's full name
     * @param experienceLevel the trading experience the client gave at registration
     * @param status the client's status
     * @param createdAt when the client registered
     * @param phone the client's phone number, or null
     * @param addressLine1 the first line of the street address
     * @param addressLine2 the second line of the street address, or null
     * @param city the city
     * @param stateRegion the state or region, or null
     * @param postalCode the postal code
     * @param countryCode the two-letter ISO country code
     * @param notifyOrderFills whether the client wants order fill emails
     * @param notifyPriceAlerts whether the client wants price alert emails
     */
    public record ClientProfileResponse(UUID clientId, String email, String fullName,
                                        String experienceLevel, String status, OffsetDateTime createdAt,
                                        String phone, String addressLine1, String addressLine2, String city,
                                        String stateRegion, String postalCode, String countryCode,
                                        boolean notifyOrderFills, boolean notifyPriceAlerts) {
    }

    /**
     * The settings a client can change; currentPassword is needed to change email or password.
     * @param fullName the client's full name
     * @param email the client's email address
     * @param phone the client's phone number, or null
     * @param addressLine1 the first line of the street address
     * @param addressLine2 the second line of the street address, or null
     * @param city the city
     * @param stateRegion the state or region, or null
     * @param postalCode the postal code
     * @param countryCode the two-letter ISO country code
     * @param notifyOrderFills whether the client wants order fill emails
     * @param notifyPriceAlerts whether the client wants price alert emails
     * @param currentPassword the client's current password, needed to change email or password
     * @param newPassword the new password, at least 8 characters, or null to keep the current one
     */
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
