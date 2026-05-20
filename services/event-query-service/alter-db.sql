-- alter-db.sql
-- Run once against the existing events_db to extend the schema for the
-- query service.  Safe to re-run: every statement uses IF NOT EXISTS or
-- checks for column existence before altering.
--
-- Apply manually:
--   psql -U platform -d events_db -f alter-db.sql
--
-- Or mount alongside init-db.sql in docker-compose (files in
-- /docker-entrypoint-initdb.d/ are executed in alphabetical order on
-- first container start — rename to 02-alter-db.sql if needed).

-- ── 1. status column ─────────────────────────────────────────────────────────
-- Tracks the processing outcome written by the query service consumer.
-- Values: 'processed' | 'failed' | 'duplicate'
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE  table_name = 'events' AND column_name = 'status'
    ) THEN
        ALTER TABLE events
            ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'processed';
    END IF;
END
$$;

-- ── 2. source_topic column ────────────────────────────────────────────────────
-- Records which Kafka topic the event arrived on (events.user, events.raw, …).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE  table_name = 'events' AND column_name = 'source_topic'
    ) THEN
        ALTER TABLE events
            ADD COLUMN source_topic VARCHAR(100);
    END IF;
END
$$;

-- ── 3. event_timestamp column ─────────────────────────────────────────────────
-- The producer-assigned timestamp from the event payload (distinct from
-- created_at which is set by the query service on write).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE  table_name = 'events' AND column_name = 'event_timestamp'
    ) THEN
        ALTER TABLE events
            ADD COLUMN event_timestamp TIMESTAMPTZ;
    END IF;
END
$$;

-- ── 4. Supporting indexes ─────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_events_status       ON events (status);
CREATE INDEX IF NOT EXISTS idx_events_source_topic ON events (source_topic);
CREATE INDEX IF NOT EXISTS idx_events_event_ts     ON events (event_timestamp DESC);

-- Composite index for the most common dashboard query pattern:
--   WHERE event_type = ? AND created_at BETWEEN ? AND ?
CREATE INDEX IF NOT EXISTS idx_events_type_created
    ON events (event_type, created_at DESC);
