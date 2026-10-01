package com.leap.leaplaughlove.account.account;

/**
 * Request to open another account for the authenticated client.
 * @param baseCurrency the currency of the new account; defaults to USD when absent
 */
public record CreateAccountRequest(String baseCurrency) {}
