-- EventFlow PostgreSQL initialisation
-- Runs once when the container first starts

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- Events table (write model for CQRS)
CREATE TABLE IF NOT EXISTS events (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_id      VARCHAR(255) NOT NULL UNIQUE,
    event_type    VARCHAR(100) NOT NULL,
    user_id       VARCHAR(128) NOT NULL,
    source        VARCHAR(100) DEFAULT 'api',
    version       VARCHAR(20)  DEFAULT '1.0',
    correlation_id VARCHAR(255),
    data          JSONB,
    metadata      JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_events_event_type  ON events (event_type);
CREATE INDEX idx_events_user_id     ON events (user_id);
CREATE INDEX idx_events_created_at  ON events (created_at DESC);
CREATE INDEX idx_events_data_gin    ON events USING GIN (data);

-- Dead-letter queue table
CREATE TABLE IF NOT EXISTS dead_letter_events (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_id      VARCHAR(255) NOT NULL,
    event_type    VARCHAR(100),
    user_id       VARCHAR(128),
    raw_payload   TEXT,
    error_reason  TEXT,
    retry_count   INTEGER DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_retried_at TIMESTAMPTZ
);

CREATE INDEX idx_dlq_created_at   ON dead_letter_events (created_at DESC);
CREATE INDEX idx_dlq_event_type   ON dead_letter_events (event_type);

-- Event metrics aggregation table
CREATE TABLE IF NOT EXISTS event_metrics_hourly (
    id            BIGSERIAL PRIMARY KEY,
    hour_bucket   TIMESTAMPTZ NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    total_count   BIGINT DEFAULT 0,
    success_count BIGINT DEFAULT 0,
    failure_count BIGINT DEFAULT 0,
    UNIQUE (hour_bucket, event_type)
);

CREATE INDEX idx_metrics_hour ON event_metrics_hourly (hour_bucket DESC);

COMMENT ON TABLE events                IS 'CQRS write model — all ingested events';
COMMENT ON TABLE dead_letter_events    IS 'Failed events awaiting reprocessing';
COMMENT ON TABLE event_metrics_hourly  IS 'Pre-aggregated hourly metrics for dashboard queries';
