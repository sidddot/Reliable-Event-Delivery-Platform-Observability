package com.eventplatform.event;

import com.eventplatform.api.BadRequestException;
import com.eventplatform.api.ResourceNotFoundException;
import com.eventplatform.api.dto.DeliverySummaryResponse;
import com.eventplatform.api.dto.EventIngestResponse;
import com.eventplatform.api.dto.EventWithDeliveriesResponse;
import com.eventplatform.api.dto.IngestEventRequest;
import com.eventplatform.delivery.DeliveryEntity;
import com.eventplatform.delivery.DeliveryRepository;
import com.eventplatform.delivery.DeliveryStatus;
import com.eventplatform.subscription.SubscriptionEntity;
import com.eventplatform.subscription.SubscriptionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final EventRepository eventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final DeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;

    public IngestService(EventRepository eventRepository,
                         SubscriptionRepository subscriptionRepository,
                         DeliveryRepository deliveryRepository,
                         ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
    }

    public EventIngestResponse ingestEvent(String idempotencyKey, IngestEventRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required");
        }

        // Fast path: Check if event already exists
        Optional<EventEntity> existing = eventRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            EventEntity event = existing.get();
            log.info("Duplicate request for idempotencyKey={}, returning existing eventId={}", idempotencyKey, event.getId());
            int deliveryCount = deliveryRepository.findByEventId(event.getId()).size();
            return new EventIngestResponse(
                    event.getId(),
                    event.getIdempotencyKey(),
                    event.getEventType(),
                    event.getPartitionKey(),
                    deliveryCount,
                    true // isDuplicate = true
            );
        }

        // Slow path: Attempt atomic fan-out inside a transaction.
        // Catch race condition (concurrent duplicate) via unique constraint.
        try {
            return doTransactionalIngest(idempotencyKey, request);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Concurrent duplicate race detected for idempotencyKey={}, recovering existing row", idempotencyKey);
            EventEntity event = eventRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Duplicate constraint triggered but row not found for key: " + idempotencyKey));
            int deliveryCount = deliveryRepository.findByEventId(event.getId()).size();
            return new EventIngestResponse(
                    event.getId(),
                    event.getIdempotencyKey(),
                    event.getEventType(),
                    event.getPartitionKey(),
                    deliveryCount,
                    true // isDuplicate = true
            );
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventIngestResponse doTransactionalIngest(String idempotencyKey, IngestEventRequest request) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(request.payload());
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Invalid JSON payload: " + e.getMessage());
        }

        EventEntity event = new EventEntity(
                idempotencyKey,
                request.eventType(),
                request.partitionKey(),
                payloadJson
        );
        EventEntity savedEvent = eventRepository.saveAndFlush(event);

        // 1. Fan-out: Query matching ACTIVE subscriptions (skips PAUSED)
        List<SubscriptionEntity> activeSubs = subscriptionRepository.findAllActive();
        List<DeliveryEntity> deliveries = new ArrayList<>();

        for (SubscriptionEntity sub : activeSubs) {
            if (sub.matchesEventType(request.eventType())) {
                DeliveryEntity delivery = new DeliveryEntity(savedEvent.getId(), sub.getId());
                delivery.setStatus(DeliveryStatus.PENDING);
                delivery.setNextAttemptAt(Instant.now());
                deliveries.add(delivery);
            }
        }

        if (!deliveries.isEmpty()) {
            deliveryRepository.saveAllAndFlush(deliveries);
        }

        log.info("Ingested eventId={} type={} fanned out to {} deliveries",
                savedEvent.getId(), savedEvent.getEventType(), deliveries.size());

        return new EventIngestResponse(
                savedEvent.getId(),
                savedEvent.getIdempotencyKey(),
                savedEvent.getEventType(),
                savedEvent.getPartitionKey(),
                deliveries.size(),
                false // isDuplicate = false (202 Accepted)
        );
    }

    @Transactional(readOnly = true)
    public EventWithDeliveriesResponse getEventWithDeliveries(UUID id) {
        EventEntity event = eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + id));

        JsonNode payloadNode;
        try {
            payloadNode = objectMapper.readTree(event.getPayload());
        } catch (JsonProcessingException e) {
            payloadNode = objectMapper.nullNode();
        }

        List<DeliverySummaryResponse> deliveries = deliveryRepository.findByEventId(id).stream()
                .map(DeliverySummaryResponse::fromEntity)
                .toList();

        return new EventWithDeliveriesResponse(
                event.getId(),
                event.getIdempotencyKey(),
                event.getEventType(),
                event.getPartitionKey(),
                payloadNode,
                event.getCreatedAt(),
                deliveries
        );
    }
}
