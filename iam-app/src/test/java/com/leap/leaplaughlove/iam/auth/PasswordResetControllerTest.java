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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetController Unit Tests")
class PasswordResetControllerTest {

    @Mock
    private PasswordResetService passwordResetService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PasswordResetController(passwordResetService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ResultActions postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    @Test
    @DisplayName("POST /api/iam/auth/forgot-password returns 204 with no body")
    void testForgotPassword() throws Exception {
        postJson("/api/iam/auth/forgot-password", new ForgotPasswordRequest("test@example.com"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(passwordResetService).requestReset("test@example.com");
    }

    @Test
    @DisplayName("POST /api/iam/auth/forgot-password with blank email returns 400 Bad Request")
    void testForgotPasswordBlankEmailValidation() throws Exception {
        postJson("/api/iam/auth/forgot-password", new ForgotPasswordRequest(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message", containsString("email is required")));

        verifyNoInteractions(passwordResetService);
    }

    @Test
    @DisplayName("POST /api/iam/auth/forgot-password with invalid email syntax returns 400 Bad Request")
    void testForgotPasswordInvalidEmailFormatValidation() throws Exception {
        postJson("/api/iam/auth/forgot-password", new ForgotPasswordRequest("not-an-email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message", containsString("email must be valid")));

        verifyNoInteractions(passwordResetService);
    }

    @Test
    @DisplayName("POST /api/iam/auth/reset-password/validate with a usable token returns 204 with no body")
    void testValidateResetToken() throws Exception {
        postJson("/api/iam/auth/reset-password/validate", new ResetTokenRequest("reset-token"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(passwordResetService).validateToken("reset-token");
    }

    @Test
    @DisplayName("POST /api/iam/auth/reset-password/validate with an unusable token returns 400")
    void testValidateResetTokenInvalid() throws Exception {
        doThrow(new InvalidResetTokenException()).when(passwordResetService).validateToken("used-token");

        postJson("/api/iam/auth/reset-password/validate", new ResetTokenRequest("used-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_RESET_TOKEN")));
    }

    @Test
    @DisplayName("POST /api/iam/auth/reset-password/validate with blank token returns 400 Bad Request")
    void testValidateResetTokenBlankValidation() throws Exception {
        postJson("/api/iam/auth/reset-password/validate", new ResetTokenRequest(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")));

        verifyNoInteractions(passwordResetService);
    }

    @Test
    @DisplayName("POST /api/iam/auth/reset-password with a valid token returns 204 with no body")
    void testResetPassword() throws Exception {
        postJson("/api/iam/auth/reset-password", new ResetPasswordRequest("reset-token", "NewPassword123"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(passwordResetService).resetPassword("reset-token", "NewPassword123");
    }

    @Test
    @DisplayName("POST /api/iam/auth/reset-password with an unusable token returns 400 and a displayable message")
    void testResetPasswordInvalidToken() throws Exception {
        doThrow(new InvalidResetTokenException())
                .when(passwordResetService).resetPassword("expired-token", "NewPassword123");

        postJson("/api/iam/auth/reset-password", new ResetPasswordRequest("expired-token", "NewPassword123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_RESET_TOKEN")))
                .andExpect(jsonPath("$.message", containsString("invalid or has expired")));
    }

    @Test
    @DisplayName("POST /api/iam/auth/reset-password with blank token returns 400 Bad Request")
    void testResetPasswordBlankTokenValidation() throws Exception {
        postJson("/api/iam/auth/reset-password", new ResetPasswordRequest("", "NewPassword123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message", containsString("token is required")));

        verifyNoInteractions(passwordResetService);
    }

    @Test
    @DisplayName("POST /api/iam/auth/reset-password with a short password returns 400 Bad Request")
    void testResetPasswordShortPasswordValidation() throws Exception {
        postJson("/api/iam/auth/reset-password", new ResetPasswordRequest("reset-token", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message", containsString("password must be at least 8 characters")));

        verifyNoInteractions(passwordResetService);
    }
}
