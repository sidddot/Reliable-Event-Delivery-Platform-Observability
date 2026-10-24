package com.eventplatform.api;

import com.eventplatform.api.dto.EventIngestResponse;
import com.eventplatform.api.dto.IngestEventRequest;
import com.eventplatform.event.IngestService;
import com.eventplatform.metrics.DeliveryMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// static imports
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;


@WebMvcTest(EventController.class)
public class EventControllerMetricsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IngestService ingestService;

    @MockBean
    private com.eventplatform.config.AppProperties appProperties;

    @MockBean
    private DeliveryMetrics deliveryMetrics;

    private final ObjectMapper objectMapper = new ObjectMapper();


    @Test
    public void ingestEvent_incrementsMetric() throws Exception {
        // Mock ingest service response (non‑duplicate case)
        EventIngestResponse mockResponse = new EventIngestResponse(
                java.util.UUID.randomUUID(),
                "key-123",
                "typeA",
                "partition-1",
                0,
                false
        );
        Mockito.when(ingestService.ingestEvent(Mockito.anyString(), any(IngestEventRequest.class)))
                .thenReturn(mockResponse);

        IngestEventRequest request = new IngestEventRequest("typeA", "partition-1",
                objectMapper.createObjectNode().put("foo", "bar"));
        String json = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/api/v1/events")
                .header("Idempotency-Key", "key-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(httpBasic("test","test"))
                )
                .andExpect(status().isAccepted());

        // Verify metric increment was called exactly once
        verify(deliveryMetrics, times(1)).incEventsIngested();
    }
}
