package com.leap.leaplaughlove.iam.client;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;

/**
 * Controller responsible for handling client registration requests and managing
 * related exceptions.
 * maps to the /api/iam/v1/clients endpoint.
 */
@RestController
@RequestMapping("/api/iam/v1/clients")
public class ClientRegistrationController {

    static final String ACCEPTED_MESSAGE = "Thanks for applying. Check your email for the next step.";

    private final ClientRegistrationService registrationService;

    /**
     * Constructs a new ClientRegistrationController with the specified
     * dependencies.
     *
     * @param registrationService the service that takes applications and opens the
     *                            client once their email is confirmed
     */
    public ClientRegistrationController(ClientRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    /**
     * Handles client registration requests. The response is the same whether or not
     * the email, phone number or SSN already belongs to a client, so this cannot be
     * used to find out which ones are registered; the applicant is told the outcome
     * by email instead.
     * 
     * @param request the client registration request containing all necessary
     *                client details
     * @return a 202 response telling the applicant to check their email
     */
    @PostMapping("/register")
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest request) {
        registrationService.submit(request);
        return accepted();
    }

    /**
     * Opens the client an application was for, using the token from the emailed
     * link. The token travels in the body to keep it out of request logs.
     * 
     * @param request the request containing the token
     * @return an empty 204 response, or 400 if the link is no longer usable
     */
    @PostMapping("/register/verify")
    public ResponseEntity<Void> verify(@Valid @RequestBody VerifyRegistrationRequest request) {
        registrationService.verify(request.token());
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<RegistrationResponse> accepted() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RegistrationResponse(ACCEPTED_MESSAGE));
    }

    /**
     * Normalizes a phone number into "(XXX) XXX-XXXX" for US numbers, with or
     * without the leading country code 1, or the digits alone otherwise. Phone
     * numbers have to be unique across clients, and clients that bypass the
     * frontend's live formatting (e.g. calling the API directly) would otherwise
     * store the same number in different formats.
     * 
     * @param phone the raw phone number as submitted
     * @return the normalized phone number, or null if it has no digits
     */
    static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() == 11 && digits.startsWith("1")) {
            digits = digits.substring(1);
        }
        if (digits.length() == 10) {
            return "(%s) %s-%s".formatted(digits.substring(0, 3), digits.substring(3, 6), digits.substring(6));
        }
        return digits.isEmpty() ? null : digits;
    }

    /**
     * Handles two applications for the same email arriving at the same moment, the
     * only way storing one can violate a constraint. The loser is answered exactly
     * like the winner.
     * 
     * @param ex the exception thrown when a data integrity violation occurs
     * @return the same 202 response a stored application gets
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<RegistrationResponse> handleDataIntegrity(
            org.springframework.dao.DataIntegrityViolationException ex) {
        return accepted();
    }

    /**
     * Represents the client registration request containing all necessary client
     * details for registering a new client in the IAM system.
     * 
     * @param email                applicant email address
     * @param phone                applicant phone number
     * @param fullName             applicant full name
     * @param dateOfBirth          applicant date of birth (must be at least 21)
     * @param ssn                  applicant Social Security Number in XXX-XX-XXXX
     *                             format
     * @param addressLine1         primary address line
     * @param addressLine2         secondary address line (optional)
     * @param city                 address city
     * @param stateRegion          address state or region
     * @param postalCode           address postal code
     * @param countryCode          two-letter ISO country code
     * @param experienceLevel      investment experience level (NOVICE,
     *                             INTERMEDIATE, ADVANCED)
     * @param initialDepositAmount initial deposit amount (minimum 5000.00)
     * @param password             the applicant's chosen sign-in password (must be
     *                             at least 8 characters)
     */
    public record RegistrationRequest(
            @NotBlank @Email String email,
            String phone,
            @NotBlank String fullName,
            // LLL-117: registration must fail if the applicant isn't at least 21 yet.
            @NotNull @MinimumAge(21) LocalDate dateOfBirth,
            // LLL-117: registration must fail if no SSN is provided, since that's what we
            // use to identify the applicant.
            @NotBlank @Pattern(regexp = "\\d{3}-\\d{2}-\\d{4}", message = "ssn must be in format XXX-XX-XXXX") String ssn,
            @NotBlank String addressLine1,
            String addressLine2,
            @NotBlank String city,
            String stateRegion,
            @NotBlank String postalCode,
            @NotBlank @Size(min = 2, max = 2) String countryCode,
            @NotBlank @Pattern(regexp = "NOVICE|INTERMEDIATE|ADVANCED") String experienceLevel,
            @NotNull @DecimalMin("5000.00") BigDecimal initialDepositAmount,
            @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String password) {
    }

    /**
     * Represents the response returned for every accepted registration request.
     * It carries nothing about the applicant, because it must not differ between
     * an application that was stored and one that was not.
     * 
     * @param message what the applicant should do next
     */
    public record RegistrationResponse(String message) {
    }

    /**
     * Represents the request made by the page the emailed link opens.
     * 
     * @param token the token from the emailed link
     */
    public record VerifyRegistrationRequest(@NotBlank String token) {
    }

    /**
     * Custom validation annotation that checks someone is old enough to register
     */
    @Target({ ElementType.FIELD, ElementType.PARAMETER })
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = MinimumAge.Validator.class)
    @interface MinimumAge {

        /**
         * Returns the minimum age
         * 
         * @return min age
         */
        int value();

        /**
         * Returns the error message
         * 
         * @return error message
         */
        String message() default "must be at least {value} years old";

        /**
         * Returns the groups
         * 
         * @return groups
         */
        Class<?>[] groups() default {};

        /**
         * Returns the payload
         * 
         * @return payload
         */
        Class<? extends Payload>[] payload() default {};

        /**
         * Compares the DOB to today's date to validate minimum age requirement
         * 
         * @param dateOfBirth the date of birth to validate
         * @param context     the context in which the constraint is evaluated
         * @return true if the date of birth meets the minimum age requirement, false
         *         otherwise
         */
        class Validator implements ConstraintValidator<MinimumAge, LocalDate> {

            private int minimumAge;

            /**
             * Initializes the validator with the minimum age specified in the annotation.
             * 
             * @param annotation the annotation containing the minimum age
             */
            @Override
            public void initialize(MinimumAge annotation) {
                this.minimumAge = annotation.value();
            }

            /**
             * Checks if the given DOB meets minimum age requirement
             * 
             * @param dateOfBirth the date of birth to validate
             * @param context     the context in which the constraint is evaluated
             * @return true if the date of birth meets the minimum age requirement, false
             *         otherwise
             */
            @Override
            public boolean isValid(LocalDate dateOfBirth, ConstraintValidatorContext context) {
                // Let @NotNull handle a missing date of birth - we only judge dates that are
                // actually present.
                if (dateOfBirth == null) {
                    return true;
                }
                return Period.between(dateOfBirth, LocalDate.now()).getYears() >= minimumAge;
            }
        }
    }
}
