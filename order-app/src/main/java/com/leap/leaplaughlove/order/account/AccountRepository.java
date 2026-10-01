package com.leap.leaplaughlove.order.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Interface repository for accessing read-only Account entities
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {
}
