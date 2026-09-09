package com.leap.leaplaughlove.iam.client;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ClientCredentialsRepository extends JpaRepository<ClientCredentials, UUID> {
    Optional<ClientCredentials> findByClientId(UUID clientId);
}
