package com.leap.leaplaughlove.repository;

import com.leap.leaplaughlove.entity.Account;
import com.leap.leaplaughlove.entity.Client;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {
    List<Account> findByClient(Client client);
    Optional<Account> findByAccountNumber(String accountNumber);
}
