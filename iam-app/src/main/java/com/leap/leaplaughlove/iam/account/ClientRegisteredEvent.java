package com.leap.leaplaughlove.iam.account;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published when a client registration is saved, so the client's first account can be opened and
 * funded once the registration has committed.
 * @param clientId the newly registered client
 * @param email the client's email, used as a claim in the token that authenticates the account calls
 * @param initialDepositAmount the amount to deposit into the client's first account
 */
public record ClientRegisteredEvent(UUID clientId, String email, BigDecimal initialDepositAmount) {}
