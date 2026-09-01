package com.leap.leaplaughlove.service;

import com.leap.leaplaughlove.entity.Account;
import com.leap.leaplaughlove.entity.Client;
import com.leap.leaplaughlove.repository.AccountRepository;
import com.leap.leaplaughlove.repository.ClientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class AccountService {
    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ClientRepository clientRepository;

    public Account createAccount(UUID clientId, Account account) {
        // Verify that the client exists
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Client not found with id: " + clientId));

        // Set the client object for the account
        account.setClient(client);
        return accountRepository.save(account);
    }

    public Optional<Account> getAccountById(UUID accountId) {
        return accountRepository.findById(accountId);
    }

    public List<Account> getAccountsByClientId(UUID clientId) {
        // Verify that the client exists
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Client not found with id: " + clientId));
        return accountRepository.findByClient(client);
    }

    public Optional<Account> getAccountByAccountNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber);
    }

    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    public Account updateAccount(UUID accountId, Account updatedAccount) {
        return accountRepository.findById(accountId)
                .map(account -> {
                    if (updatedAccount.getStatus() != null) {
                        account.setStatus(updatedAccount.getStatus());
                    }
                    if (updatedAccount.getBaseCurrency() != null) {
                        account.setBaseCurrency(updatedAccount.getBaseCurrency());
                    }
                    if (updatedAccount.getTradingEnabled() != null) {
                        account.setTradingEnabled(updatedAccount.getTradingEnabled());
                    }
                    return accountRepository.save(account);
                })
                .orElseThrow(() -> new RuntimeException("Account not found with id: " + accountId));
    }

    public void deleteAccount(UUID accountId) {
        accountRepository.deleteById(accountId);
    }

    public boolean accountBelongsToClient(UUID accountId, UUID clientId) {
        return accountRepository.findById(accountId)
                .map(account -> account.getClient() != null && account.getClient().getClientId().equals(clientId))
                .orElse(false);
    }
}
