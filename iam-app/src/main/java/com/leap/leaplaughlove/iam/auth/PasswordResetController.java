package com.leap.leaplaughlove.iam.auth;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for password recovery: requesting a reset link and using it to set a new password.
 * Both routes are public, since a client who has forgotten their password cannot sign in.
 */
@RestController
@RequestMapping("/api/iam/auth")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    /**
     * Constructs a new PasswordResetController with the specified PasswordResetService.
     * @param passwordResetService the password reset service to be used by this controller
     */
    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    /**
     * Requests a password reset link for the given email. The response is the same whether or not
     * the email belongs to a client.
     * @param request the request containing the account's email
     * @return an empty 204 response
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.noContent().build();
    }

    /**
     * Checks whether a reset link can still be used, so the page can say so before asking for a
     * new password. The token travels in the body to keep it out of request logs.
     * @param request the request containing the token
     * @return an empty 204 response, or 400 if the link is no longer usable
     */
    @PostMapping("/reset-password/validate")
    public ResponseEntity<Void> validateResetToken(@Valid @RequestBody ResetTokenRequest request) {
        passwordResetService.validateToken(request.token());
        return ResponseEntity.noContent().build();
    }

    /**
     * Sets a new password using the token from a reset link.
     * @param request the request containing the token and the new password
     * @return an empty 204 response
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
