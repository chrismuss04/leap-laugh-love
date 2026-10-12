package com.leap.leaplaughlove.account.inactivity;

/**
 * Published by every run of the nightly inactivity check, delivered once that run has committed.
 * Carries nothing: listeners read the committed flags themselves.
 */
public record InactivityCheckedEvent() {}
