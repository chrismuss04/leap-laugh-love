package com.leap.leaplaughlove.trading;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    List<Account> findByClientIdAndStatus(UUID clientId, String status);
}
