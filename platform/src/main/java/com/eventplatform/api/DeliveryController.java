package com.eventplatform.api;

import com.eventplatform.api.dto.BulkReplayResponse;
import com.eventplatform.api.dto.DeliveryAttemptResponse;
import com.eventplatform.api.dto.DeliveryResponse;
import com.eventplatform.delivery.DeliveryAttemptRepository;
import com.eventplatform.delivery.DeliveryEntity;
import com.eventplatform.delivery.DeliveryRepository;
import com.eventplatform.delivery.DeliveryStatus;
import com.eventplatform.delivery.ReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Deliveries", description = "Endpoints for delivery querying, attempt history, and DLQ replay")
public class DeliveryController {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final ReplayService replayService;

    public DeliveryController(DeliveryRepository deliveryRepository,
                              DeliveryAttemptRepository deliveryAttemptRepository,
                              ReplayService replayService) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.replayService = replayService;
    }

    @GetMapping("/deliveries")
    @Operation(summary = "Query deliveries with filtering and pagination")
    public ResponseEntity<Page<DeliveryResponse>> getDeliveries(
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(required = false) UUID subscriptionId,
            @RequestParam(required = false) UUID eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<DeliveryEntity> entities;

        if (subscriptionId != null && status != null) {
            entities = deliveryRepository.findBySubscriptionIdAndStatus(subscriptionId, status, pageable);
        } else if (subscriptionId != null) {
            entities = deliveryRepository.findBySubscriptionId(subscriptionId, pageable);
        } else if (status != null) {
            entities = deliveryRepository.findByStatus(status, pageable);
        } else {
            entities = deliveryRepository.findAll(pageable);
        }

        return ResponseEntity.ok(entities.map(DeliveryResponse::fromEntity));
    }

    @GetMapping("/deliveries/{id}/attempts")
    @Operation(summary = "Get all delivery attempt logs for a specific delivery")
    public ResponseEntity<List<DeliveryAttemptResponse>> getDeliveryAttempts(@PathVariable UUID id) {
        if (!deliveryRepository.existsById(id)) {
            throw new ResourceNotFoundException("Delivery not found: " + id);
        }
        List<DeliveryAttemptResponse> attempts = deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNoAsc(id)
                .stream()
                .map(DeliveryAttemptResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(attempts);
    }

    @PostMapping("/deliveries/{id}/replay")
    @Operation(summary = "Replay a single DEAD delivery")
    public ResponseEntity<DeliveryResponse> replayDelivery(@PathVariable UUID id) {
        DeliveryResponse response = replayService.replayDelivery(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/subscriptions/{id}/replay-dead")
    @Operation(summary = "Bulk replay all DEAD deliveries for a subscription")
    public ResponseEntity<BulkReplayResponse> replayDeadDeliveriesForSubscription(@PathVariable UUID id) {
        BulkReplayResponse response = replayService.replayDeadDeliveriesForSubscription(id);
        return ResponseEntity.ok(response);
    }
}
