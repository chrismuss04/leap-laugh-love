package com.leap.leaplaughlove.account.account;

import com.leap.leaplaughlove.account.client.ClientStatus;
import com.leap.leaplaughlove.account.client.ClientStatusRepository;
import com.leap.leaplaughlove.common.security.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Service for opening accounts on behalf of the authenticated client.
 */
@Service
public class AccountService {

    static final String DEFAULT_CURRENCY = "USD";
    static final String ACCOUNT_NUMBER_PREFIX = "ACC-";
    static final int ACCOUNT_NUMBER_SUFFIX_LENGTH = 8;
    private static final String SUFFIX_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int MAX_NUMBER_ATTEMPTS = 5;

    private final AccountRepository accountRepository;
    private final ClientStatusRepository clientStatusRepository;
    private final SecureRandom random = new SecureRandom();

    /**
     * Constructs an AccountService.
     * @param accountRepository the repository used to persist accounts
     * @param clientStatusRepository the read-only lookup of the client's status
     */
    public AccountService(AccountRepository accountRepository, ClientStatusRepository clientStatusRepository) {
        this.accountRepository = accountRepository;
        this.clientStatusRepository = clientStatusRepository;
    }

    /**
     * Opens a new account for the authenticated client. The account starts out in the state
     * that matches the client's status, so a client is never able to trade through an account
     * they could not trade through on the first one.
     * @param request the creation request; may be null, in which case USD is used
     * @return the persisted account
     */
    @Transactional
    public Account createAccount(CreateAccountRequest request) {
        UUID clientId = SecurityUtils.getAuthenticatedClientId();

        ClientStatus client = clientStatusRepository.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found"));

        String accountStatus;
        boolean tradingEnabled;
        switch (client.getStatus()) {
            case "ACTIVE" -> {
                accountStatus = "ACTIVE";
                tradingEnabled = true;
            }
            case "PENDING" -> {
                accountStatus = "PENDING";
                tradingEnabled = false;
            }
            case "LOCKED" -> {
                accountStatus = "BLOCKED";
                tradingEnabled = false;
            }
            default -> throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Client cannot open accounts");
        }

        String currency = resolveCurrency(request);

        // A null id lets Hibernate generate the UUID; a preset id would make save() merge.
        Account account = new Account(null, clientId, generateAccountNumber(), accountStatus, currency,
                tradingEnabled, OffsetDateTime.now());
        return accountRepository.save(account);
    }

    private String resolveCurrency(CreateAccountRequest request) {
        if (request == null || request.baseCurrency() == null || request.baseCurrency().isBlank()) {
            return DEFAULT_CURRENCY;
        }
        String currency = request.baseCurrency().trim().toUpperCase();
        if (!DEFAULT_CURRENCY.equals(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only USD accounts are supported");
        }
        return currency;
    }

    private String generateAccountNumber() {
        for (int attempt = 0; attempt < MAX_NUMBER_ATTEMPTS; attempt++) {
            StringBuilder number = new StringBuilder(ACCOUNT_NUMBER_PREFIX);
            for (int i = 0; i < ACCOUNT_NUMBER_SUFFIX_LENGTH; i++) {
                number.append(SUFFIX_ALPHABET.charAt(random.nextInt(SUFFIX_ALPHABET.length())));
            }
            if (!accountRepository.existsByAccountNumber(number.toString())) {
                return number.toString();
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not allocate an account number");
    }
}
