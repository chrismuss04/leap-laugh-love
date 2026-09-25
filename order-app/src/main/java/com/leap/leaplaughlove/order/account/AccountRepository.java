package com.leap.leaplaughlove.order.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

// repository interface for accessing read-only Account entities
@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {
}
