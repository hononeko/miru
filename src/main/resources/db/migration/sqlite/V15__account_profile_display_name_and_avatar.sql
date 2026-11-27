ALTER TABLE auth_accounts ADD COLUMN display_name TEXT NULL;
ALTER TABLE auth_accounts ADD COLUMN avatar_bytes BLOB NULL;
ALTER TABLE auth_accounts ADD COLUMN avatar_content_type TEXT NULL;
ALTER TABLE auth_accounts ADD COLUMN avatar_source_server_id TEXT NULL;
ALTER TABLE auth_accounts ADD COLUMN avatar_updated_at_ms INTEGER NULL;
