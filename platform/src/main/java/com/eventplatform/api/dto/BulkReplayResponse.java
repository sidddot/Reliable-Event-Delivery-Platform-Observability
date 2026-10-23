package com.eventplatform.api.dto;

import java.util.UUID;

public record BulkReplayResponse(
        UUID subscriptionId,
        int replayedCount
) {
}
