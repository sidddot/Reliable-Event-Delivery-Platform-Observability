package com.eventplatform.api;

import com.eventplatform.api.dto.EventIngestResponse;
import com.eventplatform.api.dto.EventWithDeliveriesResponse;
import com.eventplatform.api.dto.IngestEventRequest;
import com.eventplatform.event.IngestService;
import com.eventplatform.metrics.DeliveryMetrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Endpoints for durable event ingestion, idempotent fan-out, and querying")
public class EventController {

    private final DeliveryMetrics deliveryMetrics;

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final IngestService ingestService;

    public EventController(IngestService ingestService, DeliveryMetrics deliveryMetrics) {
        this.ingestService = ingestService;
        this.deliveryMetrics = deliveryMetrics;
    }

    @PostMapping
    @Operation(summary = "Ingest an event with atomic fan-out and idempotency")
    @Parameter(name = IDEMPOTENCY_KEY_HEADER, in = ParameterIn.HEADER, required = true, description = "Unique producer idempotency key")
    public ResponseEntity<EventIngestResponse> ingestEvent(
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody IngestEventRequest request) {

        // Increment metric for every ingest request received via the API
        deliveryMetrics.incEventsIngested();

        EventIngestResponse response = ingestService.ingestEvent(idempotencyKey, request);
        if (response.isDuplicate()) {
            return ResponseEntity.status(HttpStatus.OK).body(response);
        } else {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an event with all its fan-out deliveries")
    public ResponseEntity<EventWithDeliveriesResponse> getEvent(@PathVariable UUID id) {
        EventWithDeliveriesResponse response = ingestService.getEventWithDeliveries(id);
        return ResponseEntity.ok(response);
    }
}
