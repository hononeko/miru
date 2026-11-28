-- Library access checks look an item up by rating_key on every server, and the primary key leads with server_id.
-- Covers the server and section the check reads.
CREATE INDEX idx_media_items_cache_rating_key ON media_items_cache (rating_key, server_id, section_key);

-- The stale media report counts and dates the plays of each item on one server. Covers that aggregation, read in
-- rating_key order, so it needs neither the table nor a sort.
CREATE INDEX idx_playback_history_server_rating_stopped ON playback_history (server_id, rating_key, stopped_at_ms DESC);
