CREATE TABLE user_preferences (
    account_id TEXT PRIMARY KEY REFERENCES auth_accounts (id) ON DELETE CASCADE,
    theme TEXT NOT NULL DEFAULT 'system',
    time_format TEXT NOT NULL DEFAULT '24h',
    date_format TEXT NOT NULL DEFAULT 'iso',
    default_stats_range TEXT NOT NULL DEFAULT '30d',
    relative_timestamps INTEGER NOT NULL DEFAULT 1,
    updated_at_ms INTEGER NOT NULL
);
