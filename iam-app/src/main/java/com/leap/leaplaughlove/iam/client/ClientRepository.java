package com.leap.leaplaughlove.iam.client;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface for managing clients in the IAM system.
 * Provides methods to query clients by email and check for the existence of clients by email, phone number or SSN.
 */
public interface ClientRepository extends JpaRepository<Client, UUID> {
    /**
     * Retrieves a client entity by its email address.
     * @param email the email address of the client to retrieve
     * @return an Optional containing the client if found, or empty if not found
     */
    Optional<Client> findByEmail(String email);
    /**
     * Checks if a client exists with the specified email address.
     * @param email the email address to check for existence
     * @return true if a client exists with the specified email, false otherwise
     */
    boolean existsByEmail(String email);

    /**
     * Checks if a client exists with the specified SSN.
     * @param ssn the SSN to check for existence
     * @return true if a client exists with the specified SSN, false otherwise
     */
    boolean existsBySsn(String ssn);

    /**
     * Checks if a client exists with the specified phone number.
     * @param phone the normalized phone number to check for existence
     * @return true if a client exists with the specified phone number, false otherwise
     */
    boolean existsByPhone(String phone);

    /**
     * Checks if a client other than the given one has the specified phone number.
     * @param phone the normalized phone number to check for existence
     * @param clientId the client allowed to have it
     * @return true if another client has the specified phone number, false otherwise
     */
    boolean existsByPhoneAndClientIdNot(String phone, UUID clientId);

    /**
     * Retrieves the client with the specified SSN.
     * @param ssn the SSN of the client to retrieve
     * @return an Optional containing the client if found, or empty if not found
     */
    Optional<Client> findFirstBySsn(String ssn);

    /**
     * Retrieves the client with the specified phone number.
     * @param phone the normalized phone number of the client to retrieve
     * @return an Optional containing the client if found, or empty if not found
     */
    Optional<Client> findFirstByPhone(String phone);
}
