CREATE TABLE webhook_targets (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    subscribed_events TEXT NOT NULL,
    encrypted_signing_secret TEXT,
    auth_header_name TEXT,
    encrypted_auth_header_value TEXT,
    cooldown_seconds INTEGER NOT NULL DEFAULT 60,
    concurrent_stream_threshold INTEGER NOT NULL DEFAULT 3,
    buffering_spike_threshold INTEGER NOT NULL DEFAULT 3,
    buffering_window_seconds INTEGER NOT NULL DEFAULT 120,
    transcode_4k_only INTEGER NOT NULL DEFAULT 0,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE webhook_deliveries (
    id TEXT PRIMARY KEY,
    target_id TEXT NOT NULL REFERENCES webhook_targets (id) ON DELETE CASCADE,
    target_name TEXT NOT NULL,
    event_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    status TEXT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    http_status INTEGER,
    duration_ms INTEGER,
    error_message TEXT,
    created_at_ms INTEGER NOT NULL,
    completed_at_ms INTEGER
);

CREATE INDEX idx_webhook_deliveries_created_at ON webhook_deliveries (created_at_ms DESC);
CREATE INDEX idx_webhook_deliveries_target_created ON webhook_deliveries (target_id, created_at_ms DESC);
