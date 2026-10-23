-- V1__init_schema.sql: Initial schema for subscriptions, events, deliveries, and delivery_attempts

-- 1. Subscriptions Table
CREATE TABLE subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    secret VARCHAR(255) NOT NULL,
    previous_secret VARCHAR(255),
    previous_secret_expires_at TIMESTAMP WITH TIME ZONE,
    event_types TEXT[] NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    max_concurrency INT NOT NULL DEFAULT 10,
    rate_limit_per_sec INT NOT NULL DEFAULT 100,
    timeout_ms INT NOT NULL DEFAULT 5000,
    circuit_state VARCHAR(50) NOT NULL DEFAULT 'CLOSED',
    consecutive_failures INT NOT NULL DEFAULT 0,
    circuit_open_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_subscription_status CHECK (status IN ('ACTIVE', 'PAUSED')),
    CONSTRAINT chk_circuit_state CHECK (circuit_state IN ('CLOSED', 'OPEN', 'HALF_OPEN'))
);

CREATE INDEX idx_subscriptions_status ON subscriptions (status);

-- 2. Events Table
CREATE TABLE events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    event_type VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255),
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_events_event_type ON events (event_type);
CREATE INDEX idx_events_partition_key ON events (partition_key) WHERE partition_key IS NOT NULL;

-- 3. Deliveries Table
CREATE TABLE deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    subscription_id UUID NOT NULL REFERENCES subscriptions(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    locked_by VARCHAR(255),
    locked_until TIMESTAMP WITH TIME ZONE,
    last_error TEXT,
    dead_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_deliveries_event_subscription UNIQUE (event_id, subscription_id),
    CONSTRAINT chk_delivery_status CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SUCCEEDED', 'DEAD'))
);

-- Partial index for active claim query (claims PENDING rows due for attempt)
CREATE INDEX idx_deliveries_claim ON deliveries (next_attempt_at ASC)
    WHERE status = 'PENDING';

-- Partial index for lease recovery (claims IN_FLIGHT rows with expired lease)
CREATE INDEX idx_deliveries_lease_recovery ON deliveries (locked_until ASC)
    WHERE status = 'IN_FLIGHT';

-- Supporting indexes for subscription and event delivery queries
CREATE INDEX idx_deliveries_subscription_status ON deliveries (subscription_id, status);
CREATE INDEX idx_deliveries_event_id ON deliveries (event_id);

-- 4. Delivery Attempts Table
CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id UUID NOT NULL REFERENCES deliveries(id) ON DELETE CASCADE,
    attempt_no INT NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    duration_ms BIGINT NOT NULL,
    http_status INT,
    error TEXT,
    response_snippet VARCHAR(1024),
    worker_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_delivery_attempts_delivery_id ON delivery_attempts (delivery_id, attempt_no ASC);
