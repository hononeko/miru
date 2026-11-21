CREATE TABLE auth_accounts (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL,
    username_normalized TEXT NOT NULL UNIQUE,
    email TEXT,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL,
    disabled INTEGER NOT NULL DEFAULT 0,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE auth_account_media_users (
    account_id TEXT NOT NULL REFERENCES auth_accounts (id) ON DELETE CASCADE,
    media_user_id TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at_ms INTEGER NOT NULL,
    PRIMARY KEY (account_id, media_user_id)
);

CREATE INDEX idx_auth_account_media_users_media_user_id ON auth_account_media_users (media_user_id);

CREATE TABLE auth_sessions (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES auth_accounts (id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    created_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER NOT NULL,
    last_seen_at_ms INTEGER NOT NULL
);

CREATE INDEX idx_auth_sessions_account_id ON auth_sessions (account_id);
CREATE INDEX idx_auth_sessions_expires_at ON auth_sessions (expires_at_ms);

CREATE TABLE api_keys (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES auth_accounts (id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    key_prefix TEXT NOT NULL,
    key_hash TEXT NOT NULL UNIQUE,
    role TEXT NOT NULL,
    last_used_at_ms INTEGER,
    expires_at_ms INTEGER,
    revoked_at_ms INTEGER,
    created_at_ms INTEGER NOT NULL
);

CREATE INDEX idx_api_keys_account_id ON api_keys (account_id);
