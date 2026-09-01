package com.leap.leaplaughlove.iam;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Maps to both iam.clients and iam.client_profile, joined 1:1 on client_id. */
@Entity
@Table(name = "clients", schema = "iam")
@SecondaryTable(name = "client_profile", schema = "iam",
        pkJoinColumns = @PrimaryKeyJoinColumn(name = "client_id"))
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "full_name", table = "client_profile", nullable = false)
    private String fullName;

    @Column(name = "date_of_birth", table = "client_profile", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "ssn", table = "client_profile", nullable = false, unique = true)
    private String ssn;

    @Column(name = "address_line_1", table = "client_profile", nullable = false)
    private String addressLine1;

    @Column(name = "address_line_2", table = "client_profile")
    private String addressLine2;

    @Column(name = "city", table = "client_profile", nullable = false)
    private String city;

    @Column(name = "state_region", table = "client_profile")
    private String stateRegion;

    @Column(name = "postal_code", table = "client_profile", nullable = false)
    private String postalCode;

    @Column(name = "country_code", table = "client_profile", nullable = false)
    private String countryCode;

    @Column(name = "experience_level", table = "client_profile", nullable = false)
    private String experienceLevel;

    @Column(name = "initial_deposit_amount", table = "client_profile", nullable = false)
    private BigDecimal initialDepositAmount;

    @Column(name = "created_at", table = "client_profile", nullable = false, updatable = false)
    private OffsetDateTime profileCreatedAt;

    protected Client() {
    }

    public Client(UUID clientId, String email, String phone, String status, OffsetDateTime createdAt,
                  String fullName, LocalDate dateOfBirth, String ssn, String addressLine1, String addressLine2,
                  String city, String stateRegion, String postalCode, String countryCode, String experienceLevel,
                  BigDecimal initialDepositAmount) {
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
        this.profileCreatedAt = createdAt;
    }

    public UUID getClientId() {
        return clientId;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
