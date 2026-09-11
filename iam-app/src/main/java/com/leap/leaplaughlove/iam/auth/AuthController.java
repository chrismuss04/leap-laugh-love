package com.leap.leaplaughlove.iam.auth;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/**
 * Controller for handling authentication requests such as login.
 */
@RestController
@RequestMapping("/api/iam/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * Constructs a new AuthController with the specified AuthService.
     * @param authService the authentication service to be used by this controller
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }
    
    /**
     * Performs user login with the provided credentials.
     * @param request the login request containing user credentials
     * @return ResponseEntity containing the login response
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.authenticate(request.email(), request.password());
        return ResponseEntity.ok(response);
    }
}
