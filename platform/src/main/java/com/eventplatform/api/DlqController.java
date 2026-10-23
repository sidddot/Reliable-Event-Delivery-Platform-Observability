package com.eventplatform.api;

import com.eventplatform.api.dto.DeliveryResponse;
import com.eventplatform.delivery.DeliveryEntity;
import com.eventplatform.delivery.DeliveryRepository;
import com.eventplatform.delivery.DeliveryStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dlq")
@Tag(name = "DLQ", description = "Endpoints for inspecting dead-lettered deliveries")
public class DlqController {

    private final DeliveryRepository deliveryRepository;

    public DlqController(DeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    @GetMapping
    @Operation(summary = "List dead-lettered deliveries (DLQ) with optional subscription filter")
    public ResponseEntity<Page<DeliveryResponse>> getDeadLetterDeliveries(
            @RequestParam(required = false) UUID subscriptionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("deadAt").descending());
        Page<DeliveryEntity> deadDeliveries;

        if (subscriptionId != null) {
            deadDeliveries = deliveryRepository.findBySubscriptionIdAndStatus(subscriptionId, DeliveryStatus.DEAD, pageable);
        } else {
            deadDeliveries = deliveryRepository.findByStatus(DeliveryStatus.DEAD, pageable);
        }

        return ResponseEntity.ok(deadDeliveries.map(DeliveryResponse::fromEntity));
    }
}
