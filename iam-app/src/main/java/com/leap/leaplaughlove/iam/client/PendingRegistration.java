package com.leap.leaplaughlove.iam.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An application waiting for the applicant to open the link they were emailed. It becomes a
 * client only then, so an application nobody confirms never holds an email, phone number or SSN.
 * @param registrationId the unique identifier of the application
 * @param tokenHash the SHA-256 of the token in the emailed link; the token itself is never stored
 * @param email the applicant's email address
 * @param phone the applicant's normalized phone number, or null if none was given
 * @param fullName the applicant's full name
 * @param dateOfBirth the applicant's date of birth
 * @param ssn the applicant's Social Security Number
 * @param addressLine1 primary address line
 * @param addressLine2 secondary address line (optional)
 * @param city address city
 * @param stateRegion address state or region
 * @param postalCode address postal code
 * @param countryCode two-letter ISO country code
 * @param experienceLevel investment experience level
 * @param initialDepositAmount the amount to deposit into the client's first account
 * @param passwordHash the hash of the password the applicant chose
 * @param createdAt when the application was submitted
 * @param expiresAt when the emailed link stops working
 */
public record PendingRegistration(UUID registrationId, String tokenHash, String email, String phone,
                                  String fullName, LocalDate dateOfBirth, String ssn,
                                  String addressLine1, String addressLine2, String city, String stateRegion,
                                  String postalCode, String countryCode, String experienceLevel,
                                  BigDecimal initialDepositAmount, String passwordHash,
                                  Instant createdAt, Instant expiresAt) {
}
