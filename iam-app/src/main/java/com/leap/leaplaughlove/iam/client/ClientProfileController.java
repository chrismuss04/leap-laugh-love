package com.leap.leaplaughlove.iam.client;

import com.leap.leaplaughlove.common.security.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Controller exposing the authenticated client's own profile, so the UI can show who is signed
 * in without the login response or the JWT having to carry profile fields.
 * Maps to the /api/iam/v1/clients/me endpoint.
 */
@RestController
@RequestMapping("/api/iam/v1/clients")
public class ClientProfileController {

    private final ClientRepository clientRepository;

    /**
     * Constructs a new ClientProfileController with the specified client repository.
     * @param clientRepository the client repository used to look up the authenticated client
     */
    public ClientProfileController(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    /**
     * Retrieves the profile of the authenticated client. Sensitive identity fields (SSN, date of
     * birth, address) are deliberately left out - this is display data for the signed-in header.
     * @return the authenticated client's display profile
     * @throws ResponseStatusException with 404 NOT_FOUND if the token's client no longer exists
     */
    @GetMapping("/me")
    public ClientProfileResponse me() {
        UUID clientId = SecurityUtils.getAuthenticatedClientId();
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found"));
        return new ClientProfileResponse(client.getClientId(), client.getEmail(), client.getFullName(),
                client.getExperienceLevel(), client.getStatus());
    }

    /**
     * Represents the display profile of the authenticated client.
     * @param clientId the unique identifier of the client
     * @param email the client's email address
     * @param fullName the client's full name
     * @param experienceLevel the client's investment experience level (NOVICE, INTERMEDIATE, ADVANCED)
     * @param status the client's account status
     */
    public record ClientProfileResponse(UUID clientId, String email, String fullName,
                                        String experienceLevel, String status) {
    }
}
