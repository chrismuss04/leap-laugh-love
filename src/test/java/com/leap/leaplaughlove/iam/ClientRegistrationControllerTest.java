package com.leap.leaplaughlove.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// LLL-117: Client Registration - Validation user story.
// Confirms registration is rejected when the SSN is missing or the applicant is under 21,
// and still succeeds for a legitimate applicant who just turned 21.
@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/db/client_registration_test_setup.sql")
class ClientRegistrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // Builds a registration payload that passes every rule, so each test only has to
    // break the one field it's actually checking.
    private Map<String, Object> validPayload(String email) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", email);
        payload.put("phone", "555-0100");
        payload.put("fullName", "Jordan Rivera");
        payload.put("dateOfBirth", LocalDate.now().minusYears(21).toString());
        payload.put("ssn", "123-45-6789");
        payload.put("addressLine1", "123 Main St");
        payload.put("addressLine2", null);
        payload.put("city", "Springfield");
        payload.put("stateRegion", "IL");
        payload.put("postalCode", "62704");
        payload.put("countryCode", "US");
        payload.put("experienceLevel", "NOVICE");
        payload.put("initialDepositAmount", new BigDecimal("100.00"));
        return payload;
    }

    @Test
    void registersClientWhoIsExactly21() throws Exception {
        // Turning 21 today should be enough to pass - it shouldn't require being a day older.
        mockMvc.perform(post("/api/v1/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload("jordan.rivera@example.com"))))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsMissingSsn() throws Exception {
        // We rely on the SSN to identify who someone is, so a blank one should hard-fail registration.
        Map<String, Object> payload = validPayload("no.ssn@example.com");
        payload.put("ssn", "");

        String body = mockMvc.perform(post("/api/v1/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("ssn"));
    }

    @Test
    void rejectsApplicantOneDayShortOf21() throws Exception {
        // The edge case that actually proves the age math is right, not just "is this an adult".
        Map<String, Object> payload = validPayload("almost21@example.com");
        payload.put("dateOfBirth", LocalDate.now().minusYears(21).plusDays(1).toString());

        String body = mockMvc.perform(post("/api/v1/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("dateOfBirth"));
    }

    @Test
    void rejectsMinor() throws Exception {
        // A clearly-underage applicant, well away from the boundary.
        Map<String, Object> payload = validPayload("minor@example.com");
        payload.put("dateOfBirth", LocalDate.now().minusYears(10).toString());

        mockMvc.perform(post("/api/v1/clients/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }
}
