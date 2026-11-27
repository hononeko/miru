ALTER TABLE auth_accounts ADD COLUMN display_name TEXT NULL;
ALTER TABLE auth_accounts ADD COLUMN avatar_bytes BYTEA NULL;
ALTER TABLE auth_accounts ADD COLUMN avatar_content_type TEXT NULL;
ALTER TABLE auth_accounts ADD COLUMN avatar_source_server_id TEXT NULL;
ALTER TABLE auth_accounts ADD COLUMN avatar_updated_at_ms BIGINT NULL;
