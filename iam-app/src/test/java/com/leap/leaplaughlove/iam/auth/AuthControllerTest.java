package com.leap.leaplaughlove.iam.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leap.leaplaughlove.iam.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController Unit Tests")
class AuthControllerTest {

    @Mock
    private AuthService authService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AuthController authController = new AuthController(authService);
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/iam/auth/login with valid credentials returns 200 and LoginResponse")
    void testLoginSuccess() throws Exception {
        LoginRequest request = new LoginRequest("test@example.com", "SecretPass123!");
        LoginResponse response = new LoginResponse("mock-access-token", "Bearer", 3600L);

        when(authService.authenticate("test@example.com", "SecretPass123!")).thenReturn(response);

        mockMvc.perform(post("/api/iam/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", is("mock-access-token")))
                .andExpect(jsonPath("$.tokenType", is("Bearer")))
                .andExpect(jsonPath("$.expiresInSeconds", is(3600)));

        verify(authService).authenticate("test@example.com", "SecretPass123!");
    }

    @Test
    @DisplayName("POST /api/iam/auth/login with invalid password returns 401 Unauthorized")
    void testLoginInvalidCredentials() throws Exception {
        LoginRequest request = new LoginRequest("test@example.com", "WrongPassword");

        when(authService.authenticate("test@example.com", "WrongPassword"))
                .thenThrow(new InvalidCredentialsException("Invalid email or password"));

        mockMvc.perform(post("/api/iam/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("INVALID_CREDENTIALS")))
                .andExpect(jsonPath("$.message", is("Invalid email or password")));
    }

    @Test
    @DisplayName("POST /api/iam/auth/login with locked account returns 423 Locked")
    void testLoginAccountLocked() throws Exception {
        LoginRequest request = new LoginRequest("test@example.com", "SecretPass123!");

        when(authService.authenticate("test@example.com", "SecretPass123!"))
                .thenThrow(new AccountLockedException("Account is locked due to too many failed login attempts"));

        mockMvc.perform(post("/api/iam/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error", is("ACCOUNT_LOCKED")))
                .andExpect(jsonPath("$.message", containsString("Account is locked")));
    }

    @Test
    @DisplayName("POST /api/iam/auth/login with blank email returns 400 Bad Request")
    void testLoginBlankEmailValidation() throws Exception {
        LoginRequest request = new LoginRequest("", "SecretPass123!");

        mockMvc.perform(post("/api/iam/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message", containsString("email is required")));
    }

    @Test
    @DisplayName("POST /api/iam/auth/login with invalid email syntax returns 400 Bad Request")
    void testLoginInvalidEmailFormatValidation() throws Exception {
        LoginRequest request = new LoginRequest("not-an-email", "SecretPass123!");

        mockMvc.perform(post("/api/iam/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message", containsString("email must be valid")));
    }

    @Test
    @DisplayName("POST /api/iam/auth/login with blank password returns 400 Bad Request")
    void testLoginBlankPasswordValidation() throws Exception {
        LoginRequest request = new LoginRequest("test@example.com", "");

        mockMvc.perform(post("/api/iam/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message", containsString("password is required")));
    }
}

