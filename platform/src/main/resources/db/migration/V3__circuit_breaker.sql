-- V3__circuit_breaker.sql
-- Add circuit‑breaker columns to subscriptions table
ALTER TABLE subscriptions ADD COLUMN circuit_state VARCHAR(20) NOT NULL DEFAULT 'CLOSED';
ALTER TABLE subscriptions ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0;
ALTER TABLE subscriptions ADD COLUMN circuit_open_until TIMESTAMP;
