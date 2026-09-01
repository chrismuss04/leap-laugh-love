package com.leap.leaplaughlove.iam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ClientCredentialsRepository extends JpaRepository<ClientCredentials, UUID> {
}
