package com.leap.leaplaughlove.iam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClientRepository extends JpaRepository<Client, UUID> {
    boolean existsByEmail(String email);

    boolean existsBySsn(String ssn);

    Optional<Client> findByEmailIgnoreCase(String email);
}
