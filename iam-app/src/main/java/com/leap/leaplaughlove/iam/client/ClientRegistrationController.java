package com.leap.leaplaughlove.iam.client;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.UUID;

/** 
 * Controller responsible for handling client registration requests and managing related exceptions.
 * maps to the /api/iam/v1/clients endpoint.
 */
@RestController
@RequestMapping("/api/iam/v1/clients")
public class ClientRegistrationController {

    private final ClientRepository clientRepository;

    /**
     * Constructs a new ClientRegistrationController with the specified client repository.
     * @param clientRepository the client repository used for persisting and retrieving client data
     */
    public ClientRegistrationController(ClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    /**
     * Handles client registration requests
     * @param  request the client registration request containing all necessary client details
     * @return RegistrationResponse containing the registration response with client ID, email, and status
     */
    @PostMapping("/register")
    @Transactional
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest request) {
        if (clientRepository.existsByEmail(request.email())) {
            throw new DuplicateClientException("email already registered");
        }
        if (clientRepository.existsBySsn(request.ssn())) {
            throw new DuplicateClientException("ssn already registered");
        }

        OffsetDateTime now = OffsetDateTime.now();
        Client client = new Client(
                UUID.randomUUID(), request.email(), request.phone(), "PENDING", now,
                request.fullName(), request.dateOfBirth(), request.ssn(),
                request.addressLine1(), request.addressLine2(), request.city(),
                request.stateRegion(), request.postalCode(), request.countryCode(),
                request.experienceLevel(), request.initialDepositAmount());
        clientRepository.save(client);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegistrationResponse(client.getClientId(), client.getEmail(), client.getStatus()));
    }

    /**
     * Handles duplicate client exceptions thrown when client already exists
     * @param  ex the exception thrown when a duplicate client is detected
     * @return ResponseEntity containing the error message and HTTP status code
     */
    @ExceptionHandler(DuplicateClientException.class)
    public ResponseEntity<String> handleDuplicate(DuplicateClientException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    /**
     * Handles attempts to violate data integrity constraints, such as duplicate email or SSN entries.
     * @param  ex the exception thrown when a data integrity violation occurs
     * @return ResponseEntity containing the error message and HTTP status code
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<String> handleDataIntegrity(org.springframework.dao.DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body("email or ssn already registered");
    }

    /**
     * Represents the client registration request containing all necessary client details for registering a new client in the IAM system.
     */
    public record RegistrationRequest(
            @NotBlank @Email String email,
            String phone,
            @NotBlank String fullName,
            // LLL-117: registration must fail if the applicant isn't at least 21 yet.
            @NotNull @MinimumAge(21) LocalDate dateOfBirth,
            // LLL-117: registration must fail if no SSN is provided, since that's what we use to identify the applicant.
            @NotBlank @Pattern(regexp = "\\d{3}-\\d{2}-\\d{4}", message = "ssn must be in format XXX-XX-XXXX") String ssn,
            @NotBlank String addressLine1,
            String addressLine2,
            @NotBlank String city,
            String stateRegion,
            @NotBlank String postalCode,
            @NotBlank @Size(min = 2, max = 2) String countryCode,
            @NotBlank @Pattern(regexp = "NOVICE|INTERMEDIATE|ADVANCED") String experienceLevel,
            @NotNull @DecimalMin("0.00") BigDecimal initialDepositAmount) {
    }

    /**
     * Represents the response returned after a successful client registration.
     * @param clientId the unique identifier of the newly registered client
     * @param email the email of the newly registered client
     * @param status the registration status of the client
     */
    public record RegistrationResponse(UUID clientId, String email, String status) {
    }

    static class DuplicateClientException extends RuntimeException {
        DuplicateClientException(String message) {
            super(message);
        }
    }

    // LLL-117: custom validation rule that checks someone is old enough to register.
    // We can't use a built-in annotation for this because "born before today" (@Past) isn't the same
    // thing as "at least 21 years old" - this one actually does the birthday math.
    @Target({ElementType.FIELD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = MinimumAge.Validator.class)
    @interface MinimumAge {

        int value();

        String message() default "must be at least {value} years old";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};

        // Does the actual work of comparing the date of birth against today's date.
        class Validator implements ConstraintValidator<MinimumAge, LocalDate> {

            private int minimumAge;

            /**
             * Initializes the validator with the minimum age specified in the annotation.
             * @param MinimumAge annotation 
             */
            @Override
            public void initialize(MinimumAge annotation) {
                this.minimumAge = annotation.value();
            }

            /**            
             * Checks if the given DOB meets minimum age requirement
             * @param dateOfBirth the date of birth to validate
             * @param context the context in which the constraint is evaluated
             * @return true if the date of birth meets the minimum age requirement, false otherwise
             */
            @Override
            public boolean isValid(LocalDate dateOfBirth, ConstraintValidatorContext context) {
                // Let @NotNull handle a missing date of birth - we only judge dates that are actually present.
                if (dateOfBirth == null) {
                    return true;
                }
                return Period.between(dateOfBirth, LocalDate.now()).getYears() >= minimumAge;
            }
        }
    }
}
