package com.leap.leaplaughlove.iam.client;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Client entity representing client in IAM
 */
@Entity
@Table(name = "clients", schema = "iam")
@SecondaryTable(name = "client_profile", schema = "iam", pkJoinColumns = @PrimaryKeyJoinColumn(name = "client_id"))
public class Client {

    @Id
    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(table = "client_profile", name = "full_name", nullable = false)
    private String fullName;

    @Column(table = "client_profile", name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(table = "client_profile", name = "ssn", nullable = false, unique = true)
    private String ssn;

    @Column(table = "client_profile", name = "address_line1", nullable = false)
    private String addressLine1;

    @Column(table = "client_profile", name = "address_line2")
    private String addressLine2;

    @Column(table = "client_profile", name = "city", nullable = false)
    private String city;

    @Column(table = "client_profile", name = "state_region")
    private String stateRegion;

    @Column(table = "client_profile", name = "postal_code", nullable = false)
    private String postalCode;

    @Column(table = "client_profile", name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(table = "client_profile", name = "experience_level", nullable = false)
    private String experienceLevel;

    @Column(table = "client_profile", name = "initial_deposit_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal initialDepositAmount;

    /**
     * Protected no-argument constructor for JPA.
     */
    protected Client() {
    }

    /**
     *  Method to create a new Client entity with the specified details.
     * @param clientId the unique identifier of the client
     * @param email the email address of the client
     * @param phone the phone number of the client
     * @param status the status of the client (e.g., active, inactive)
     * @param createdAt the timestamp when the client was created
     * @param fullName the full name of the client
     * @param dateOfBirth the date of birth of the client
     * @param ssn the social security number of the client
     * @param addressLine1 the first line of the client's address
     * @param addressLine2 the second line of the client's address (optional)
     * @param city the city of the client's address
     * @param stateRegion the state or region of the client's address
     * @param postalCode the postal code of the client's address
     * @param countryCode the country code of the client's address
     * @param experienceLevel the experience level of the client
     * @param initialDepositAmount the initial deposit amount of the client
     */
    public Client(UUID clientId, String email, String phone, String status, OffsetDateTime createdAt,
                  String fullName, LocalDate dateOfBirth, String ssn,
                  String addressLine1, String addressLine2, String city, String stateRegion,
                  String postalCode, String countryCode, String experienceLevel, BigDecimal initialDepositAmount) {
        this.clientId = clientId;
        this.email = email;
        this.phone = phone;
        this.status = status;
        this.createdAt = createdAt;
        this.fullName = fullName;
        this.dateOfBirth = dateOfBirth;
        this.ssn = ssn;
        this.addressLine1 = addressLine1;
        this.addressLine2 = addressLine2;
        this.city = city;
        this.stateRegion = stateRegion;
        this.postalCode = postalCode;
        this.countryCode = countryCode;
        this.experienceLevel = experienceLevel;
        this.initialDepositAmount = initialDepositAmount;
    }
    /**
     * Method to retrieve the unique identifier of the client.
     * @return the client ID
     */
    public UUID getClientId() { return clientId; }
    /**
     * Method to retrieve the email address of the client.
     * @return the email address of the client
     */
    public String getEmail() { return email; }
    /**
     * Method to retrieve the phone number of the client.
     * @return the phone number of the client
     */
    public String getPhone() { return phone; }

    /**
     * Method to retrieve the status of the client.
     * @return the status of the client
     */
    public String getStatus() { return status; }

    /**
     * Method to update the status of the client.
     * @param status the new status of the client
     */
    public void setStatus(String status) { this.status = status; }

    /**
     * Method to retrieve the creation timestamp of the client.
     * @return the creation timestamp of the client
     */
    public OffsetDateTime getCreatedAt() { return createdAt; }
    /**
     * Method to retrieve the full name of the client.
     * @return the full name of the client
     */
    public String getFullName() { return fullName; }
    /**
     * Method to retrieve the date of birth of the client.
     * @return the date of birth of the client
     */
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    /**
     * Method to retrieve the social security number of the client.
     * @return the social security number of the client
     */
    public String getSsn() { return ssn; }
    /**
     * Method to retrieve the first line of the client's address.
     * @return the first line of the client's address
     */
    public String getAddressLine1() { return addressLine1; }
    /**
     * Method to retrieve the second line of the client's address.
     * @return the second line of the client's address
     */
    public String getAddressLine2() { return addressLine2; }
    /**
     * Method to retrieve the city of the client's address.
     * @return the city of the client's address
     */
    public String getCity() { return city; }
    /**
     * Method to retrieve the state or region of the client's address.
     * @return the state or region of the client's address
     */
    public String getStateRegion() { return stateRegion; }
    /**
     * Method to retrieve the postal code of the client's address.
     * @return the postal code of the client's address
     */
    public String getPostalCode() { return postalCode; }
    /**
     * Method to retrieve the country code of the client's address.
     * @return the country code of the client's address
     */
    public String getCountryCode() { return countryCode; }
    /**
     * Method to retrieve the experience level of the client.
     * @return the experience level of the client
     */
    public String getExperienceLevel() { return experienceLevel; }
    /**
     * Method to retrieve the initial deposit amount of the client.
     * @return the initial deposit amount of the client
     */
    public BigDecimal getInitialDepositAmount() { return initialDepositAmount; }
}
