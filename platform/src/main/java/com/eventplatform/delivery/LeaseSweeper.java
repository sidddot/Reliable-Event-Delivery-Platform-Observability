package com.eventplatform.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class LeaseSweeper {

    private static final Logger log = LoggerFactory.getLogger(LeaseSweeper.class);

    private final JdbcTemplate jdbcTemplate;

    private static final String SWEEP_EXPIRED_LEASES_QUERY = """
        UPDATE deliveries
        SET status = 'PENDING',
            locked_by = NULL,
            locked_until = NULL,
            updated_at = ?
        WHERE status = 'IN_FLIGHT'
          AND locked_until < ?
        """;

    public LeaseSweeper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelayString = "${app.worker.lease-sweep-interval-ms:5000}")
    @Transactional
    public int sweepExpiredLeases() {
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        int recovered = jdbcTemplate.update(SWEEP_EXPIRED_LEASES_QUERY, nowTs, nowTs);
        if (recovered > 0) {
            log.warn("LeaseSweeper recovered {} deliveries with expired worker leases back to PENDING", recovered);
        }
        return recovered;
    }
}
