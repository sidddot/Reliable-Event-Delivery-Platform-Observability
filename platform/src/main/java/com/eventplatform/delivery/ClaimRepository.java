package com.eventplatform.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ClaimRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String CLAIM_QUERY = """
        WITH claimable AS (
            SELECT d.id
            FROM deliveries d
            JOIN subscriptions s ON d.subscription_id = s.id
            WHERE s.status = 'ACTIVE'
              AND (
                  (d.status = 'PENDING' AND d.next_attempt_at <= ?)
                  OR
                  (d.status = 'IN_FLIGHT' AND d.locked_until < ?)
              )
            ORDER BY d.next_attempt_at ASC
            LIMIT ?
            FOR UPDATE OF d SKIP LOCKED
        )
        UPDATE deliveries d
        SET status = 'IN_FLIGHT',
            locked_by = ?,
            locked_until = ?,
            updated_at = ?
        FROM claimable c
        WHERE d.id = c.id
        RETURNING d.id, d.event_id, d.subscription_id, d.status, d.attempt_count, d.next_attempt_at,
                  d.locked_by, d.locked_until, d.last_error, d.dead_at, d.created_at, d.updated_at
        """;

    public ClaimRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public List<DeliveryEntity> claimDueDeliveries(String workerId, int batchSize, int leaseSeconds) {
        Instant now = Instant.now();
        Instant lockedUntil = now.plusSeconds(leaseSeconds);

        Timestamp nowTimestamp = Timestamp.from(now);
        Timestamp lockedUntilTimestamp = Timestamp.from(lockedUntil);

        return jdbcTemplate.query(
                CLAIM_QUERY,
                new DeliveryRowMapper(),
                nowTimestamp,
                nowTimestamp,
                batchSize,
                workerId,
                lockedUntilTimestamp,
                nowTimestamp
        );
    }

    private static class DeliveryRowMapper implements RowMapper<DeliveryEntity> {
        @Override
        public DeliveryEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            DeliveryEntity entity = new DeliveryEntity();
            entity.setId(rs.getObject("id", UUID.class));
            entity.setEventId(rs.getObject("event_id", UUID.class));
            entity.setSubscriptionId(rs.getObject("subscription_id", UUID.class));
            entity.setStatus(DeliveryStatus.valueOf(rs.getString("status")));
            entity.setAttemptCount(rs.getInt("attempt_count"));

            Timestamp nextAttempt = rs.getTimestamp("next_attempt_at");
            if (nextAttempt != null) {
                entity.setNextAttemptAt(nextAttempt.toInstant());
            }

            entity.setLockedBy(rs.getString("locked_by"));

            Timestamp lockedUntil = rs.getTimestamp("locked_until");
            if (lockedUntil != null) {
                entity.setLockedUntil(lockedUntil.toInstant());
            }

            entity.setLastError(rs.getString("last_error"));

            Timestamp deadAt = rs.getTimestamp("dead_at");
            if (deadAt != null) {
                entity.setDeadAt(deadAt.toInstant());
            }

            Timestamp createdAt = rs.getTimestamp("created_at");
            if (createdAt != null) {
                entity.setCreatedAt(createdAt.toInstant());
            }

            Timestamp updatedAt = rs.getTimestamp("updated_at");
            if (updatedAt != null) {
                entity.setUpdatedAt(updatedAt.toInstant());
            }

            return entity;
        }
    }
}
