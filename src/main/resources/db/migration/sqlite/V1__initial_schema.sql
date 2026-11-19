CREATE TABLE media_servers (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    server_type TEXT NOT NULL,
    machine_id TEXT NOT NULL UNIQUE,
    base_url TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE users (
    id TEXT PRIMARY KEY,
    server_id TEXT NOT NULL REFERENCES media_servers (id) ON DELETE CASCADE,
    external_user_id TEXT NOT NULL,
    username TEXT NOT NULL,
    display_name TEXT,
    email TEXT,
    thumb_url TEXT,
    is_admin INTEGER NOT NULL DEFAULT 0,
    last_seen_at_ms INTEGER,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    UNIQUE (server_id, external_user_id)
);

CREATE TABLE active_sessions (
    server_id TEXT NOT NULL REFERENCES media_servers (id) ON DELETE CASCADE,
    session_key TEXT NOT NULL,
    rating_key TEXT NOT NULL,
    session_id TEXT,
    user_id TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    media_type TEXT NOT NULL,
    title TEXT NOT NULL,
    parent_title TEXT,
    grandparent_title TEXT,
    parent_index INTEGER,
    media_index INTEGER,
    year INTEGER,
    thumb_path TEXT,
    library_section_key TEXT,
    duration_ms INTEGER NOT NULL,
    view_offset_ms INTEGER NOT NULL,
    state TEXT NOT NULL,
    stream_decision TEXT NOT NULL,
    video_decision TEXT,
    audio_decision TEXT,
    hw_transcode INTEGER NOT NULL DEFAULT 0,
    video_codec TEXT,
    audio_codec TEXT,
    video_resolution TEXT,
    bandwidth_kbps INTEGER,
    player_name TEXT,
    player_platform TEXT,
    player_product TEXT,
    player_machine_id TEXT,
    ip_address TEXT,
    location TEXT,
    started_at_ms INTEGER NOT NULL,
    last_seen_at_ms INTEGER NOT NULL,
    paused_duration_sec INTEGER NOT NULL DEFAULT 0,
    missed_polls INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (server_id, session_key, rating_key)
);

CREATE INDEX idx_active_sessions_user_id ON active_sessions (user_id);

CREATE TABLE playback_history (
    id TEXT PRIMARY KEY,
    server_id TEXT NOT NULL REFERENCES media_servers (id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    external_import_key TEXT UNIQUE,
    rating_key TEXT NOT NULL,
    media_type TEXT NOT NULL,
    title TEXT NOT NULL,
    parent_title TEXT,
    grandparent_title TEXT,
    parent_index INTEGER,
    media_index INTEGER,
    year INTEGER,
    thumb_path TEXT,
    library_section_key TEXT,
    duration_ms INTEGER NOT NULL,
    view_offset_ms INTEGER NOT NULL,
    watched_duration_sec INTEGER NOT NULL,
    paused_duration_sec INTEGER NOT NULL DEFAULT 0,
    completion_percent REAL NOT NULL,
    is_watched INTEGER NOT NULL DEFAULT 0,
    stream_decision TEXT NOT NULL,
    video_decision TEXT,
    audio_decision TEXT,
    hw_transcode INTEGER NOT NULL DEFAULT 0,
    video_codec TEXT,
    audio_codec TEXT,
    video_resolution TEXT,
    bandwidth_kbps INTEGER,
    player_name TEXT,
    player_platform TEXT,
    player_product TEXT,
    player_machine_id TEXT,
    ip_address TEXT,
    location TEXT,
    started_at_ms INTEGER NOT NULL,
    stopped_at_ms INTEGER NOT NULL
);

CREATE INDEX idx_playback_history_user_stopped ON playback_history (user_id, stopped_at_ms DESC);
CREATE INDEX idx_playback_history_server_stopped ON playback_history (server_id, stopped_at_ms DESC);
CREATE INDEX idx_playback_history_rating_key ON playback_history (rating_key);
CREATE INDEX idx_playback_history_type_stopped ON playback_history (media_type, stopped_at_ms DESC);

CREATE TABLE library_sections (
    server_id TEXT NOT NULL REFERENCES media_servers (id) ON DELETE CASCADE,
    section_key TEXT NOT NULL,
    title TEXT NOT NULL,
    section_type TEXT NOT NULL,
    item_count INTEGER NOT NULL DEFAULT 0,
    child_count INTEGER NOT NULL DEFAULT 0,
    leaf_count INTEGER NOT NULL DEFAULT 0,
    total_size_bytes INTEGER NOT NULL DEFAULT 0,
    total_duration_ms INTEGER NOT NULL DEFAULT 0,
    last_synced_at_ms INTEGER,
    updated_at_ms INTEGER NOT NULL,
    PRIMARY KEY (server_id, section_key)
);

CREATE TABLE library_snapshots (
    id TEXT PRIMARY KEY,
    server_id TEXT NOT NULL,
    section_key TEXT NOT NULL,
    captured_at_ms INTEGER NOT NULL,
    item_count INTEGER NOT NULL,
    child_count INTEGER NOT NULL,
    leaf_count INTEGER NOT NULL,
    total_size_bytes INTEGER NOT NULL,
    total_duration_ms INTEGER NOT NULL,
    aggregates_json TEXT,
    FOREIGN KEY (server_id, section_key) REFERENCES library_sections (server_id, section_key) ON DELETE CASCADE
);

CREATE INDEX idx_library_snapshots_section_captured ON library_snapshots (server_id, section_key, captured_at_ms DESC);

CREATE TABLE media_items_cache (
    server_id TEXT NOT NULL REFERENCES media_servers (id) ON DELETE CASCADE,
    rating_key TEXT NOT NULL,
    section_key TEXT NOT NULL,
    media_type TEXT NOT NULL,
    title TEXT NOT NULL,
    parent_title TEXT,
    grandparent_title TEXT,
    year INTEGER,
    thumb_path TEXT,
    duration_ms INTEGER,
    file_size_bytes INTEGER,
    video_resolution TEXT,
    video_codec TEXT,
    audio_codec TEXT,
    added_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    PRIMARY KEY (server_id, rating_key)
);

CREATE INDEX idx_media_items_cache_section_added ON media_items_cache (server_id, section_key, added_at_ms DESC);
