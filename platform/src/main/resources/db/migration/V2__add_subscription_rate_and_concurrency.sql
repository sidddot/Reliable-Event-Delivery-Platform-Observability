-- V2__add_subscription_rate_and_concurrency.sql
-- Add per‑subscription concurrency and rate‑limit columns
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS max_concurrency INT NOT NULL DEFAULT 10;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS rate_limit_per_sec INT NOT NULL DEFAULT 100;
