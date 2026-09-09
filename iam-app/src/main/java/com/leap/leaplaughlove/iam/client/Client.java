package com.leap.leaplaughlove.iam.client;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

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

    protected Client() {
    }

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

    public UUID getClientId() { return clientId; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getFullName() { return fullName; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getSsn() { return ssn; }
    public String getAddressLine1() { return addressLine1; }
    public String getAddressLine2() { return addressLine2; }
    public String getCity() { return city; }
    public String getStateRegion() { return stateRegion; }
    public String getPostalCode() { return postalCode; }
    public String getCountryCode() { return countryCode; }
    public String getExperienceLevel() { return experienceLevel; }
    public BigDecimal getInitialDepositAmount() { return initialDepositAmount; }
}
