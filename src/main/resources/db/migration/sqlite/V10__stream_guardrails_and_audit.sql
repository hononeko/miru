CREATE TABLE guardrail_rules (
    rule_type TEXT PRIMARY KEY,
    mode TEXT NOT NULL,
    threshold_int INTEGER,
    message TEXT NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    updated_by TEXT
);

CREATE TABLE stream_termination_audit (
    id TEXT PRIMARY KEY,
    created_at_ms INTEGER NOT NULL,
    server_id TEXT,
    session_id TEXT NOT NULL,
    session_key TEXT,
    source TEXT NOT NULL,
    action TEXT NOT NULL,
    rule_type TEXT,
    actor_account_id TEXT,
    actor_name TEXT NOT NULL,
    target_user_id TEXT,
    target_username TEXT,
    media_rating_key TEXT,
    media_title TEXT NOT NULL,
    stream_decision TEXT,
    video_resolution TEXT,
    reason TEXT NOT NULL,
    error_detail TEXT
);

CREATE INDEX idx_stream_termination_audit_created_at ON stream_termination_audit (created_at_ms DESC);
CREATE INDEX idx_stream_termination_audit_source_created ON stream_termination_audit (source, created_at_ms DESC);
