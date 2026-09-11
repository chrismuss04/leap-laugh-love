package com.leap.leaplaughlove.iam.client;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface for managing clients in the IAM system.
 * Provides methods to query clients by email and check for the existence of clients by email or SSN.
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
}
