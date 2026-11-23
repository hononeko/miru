ALTER TABLE user_preferences
    ADD COLUMN analytics_metric TEXT NOT NULL DEFAULT 'plays';
