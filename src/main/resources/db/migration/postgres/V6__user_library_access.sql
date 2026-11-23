CREATE TABLE user_library_access (
    server_id TEXT NOT NULL REFERENCES media_servers (id) ON DELETE CASCADE,
    external_user_id TEXT NOT NULL,
    all_libraries BOOLEAN NOT NULL DEFAULT FALSE,
    section_keys_json TEXT NOT NULL DEFAULT '[]',
    updated_at_ms BIGINT NOT NULL,
    PRIMARY KEY (server_id, external_user_id)
);
