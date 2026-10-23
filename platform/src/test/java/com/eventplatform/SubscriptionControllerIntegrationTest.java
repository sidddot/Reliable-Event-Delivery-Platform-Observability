package com.eventplatform;

import com.eventplatform.api.dto.CreateSubscriptionRequest;
import com.eventplatform.api.dto.RotateSecretRequest;
import com.eventplatform.api.dto.UpdateSubscriptionRequest;
import com.eventplatform.subscription.SubscriptionRepository;
import com.eventplatform.subscription.SubscriptionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class SubscriptionControllerIntegrationTest {

    private static final String API_KEY = "test-api-key-test-environment-123456";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("events_db")
            .withUsername("test_user")
            .withPassword("test_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
    }

    @Test
    @DisplayName("Requests to /api/v1/** without or with wrong X-API-Key must return 401 Unauthorized")
    void testAuthenticationRequired() throws Exception {
        // Missing API key header
        mockMvc.perform(get("/api/v1/subscriptions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        // Invalid API key header
        mockMvc.perform(get("/api/v1/subscriptions")
                        .header("X-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Validation rejection on invalid URL or empty event types")
    void testValidationErrors() throws Exception {
        // Invalid URL format
        CreateSubscriptionRequest invalidUrlReq = new CreateSubscriptionRequest(
                "Invalid URL Sub",
                "ftp://invalid-protocol.com",
                List.of("*"),
                10,
                100,
                5000
        );

        mockMvc.perform(post("/api/v1/subscriptions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidUrlReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details.url").exists());

        // Empty event types
        CreateSubscriptionRequest emptyTypesReq = new CreateSubscriptionRequest(
                "Empty Types Sub",
                "https://api.example.com/webhook",
                List.of(),
                10,
                100,
                5000
        );

        mockMvc.perform(post("/api/v1/subscriptions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyTypesReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details.eventTypes").exists());
    }

    @Test
    @DisplayName("Full subscription CRUD lifecycle: Create, Read, Update/Pause, Delete")
    void testSubscriptionCrudLifecycle() throws Exception {
        // 1. Create
        CreateSubscriptionRequest createReq = new CreateSubscriptionRequest(
                "Billing Webhook",
                "https://billing.example.com/hooks",
                List.of("invoice.paid", "invoice.payment_failed"),
                20,
                200,
                3000
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/subscriptions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Billing Webhook"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andReturn();

        JsonNode json = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String subId = json.get("id").asText();
        String initialSecret = json.get("secret").asText();
        assertThat(initialSecret).startsWith("whsec_");

        // 2. Read by ID
        mockMvc.perform(get("/api/v1/subscriptions/{id}", subId)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Billing Webhook"))
                .andExpect(jsonPath("$.eventTypes[0]").value("invoice.paid"));

        // 3. List
        mockMvc.perform(get("/api/v1/subscriptions")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 4. Update / Pause
        UpdateSubscriptionRequest updateReq = new UpdateSubscriptionRequest(
                "Billing Webhook Paused",
                null,
                null,
                SubscriptionStatus.PAUSED,
                null,
                null,
                null
        );

        mockMvc.perform(patch("/api/v1/subscriptions/{id}", subId)
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Billing Webhook Paused"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // 5. Delete
        mockMvc.perform(delete("/api/v1/subscriptions/{id}", subId)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isNoContent());

        // Subsequent GET must return 404
        mockMvc.perform(get("/api/v1/subscriptions/{id}", subId)
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("Rotate secret preserves previous secret with expiration window")
    void testRotateSecret() throws Exception {
        CreateSubscriptionRequest createReq = new CreateSubscriptionRequest(
                "Rotate Secret Sub",
                "https://api.example.com/endpoint",
                List.of("*"),
                null,
                null,
                null
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/subscriptions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode initialJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String subId = initialJson.get("id").asText();
        String oldSecret = initialJson.get("secret").asText();

        // Rotate secret with 48 hours retention
        RotateSecretRequest rotateReq = new RotateSecretRequest(48);
        MvcResult rotateResult = mockMvc.perform(post("/api/v1/subscriptions/{id}/rotate-secret", subId)
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rotateReq)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode rotatedJson = objectMapper.readTree(rotateResult.getResponse().getContentAsString());
        String newSecret = rotatedJson.get("secret").asText();
        String previousSecret = rotatedJson.get("previousSecret").asText();
        String previousExpiresAt = rotatedJson.get("previousSecretExpiresAt").asText();

        assertThat(newSecret).isNotEqualTo(oldSecret);
        assertThat(newSecret).startsWith("whsec_");
        assertThat(previousSecret).isEqualTo(oldSecret);
        assertThat(previousExpiresAt).isNotNull();
    }
}
