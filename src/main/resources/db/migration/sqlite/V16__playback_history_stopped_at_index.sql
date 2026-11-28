-- Unscoped admin reads (the history list, overview, heatmap and client stats with no server or user filter) filter and
-- sort on stopped_at_ms alone, which none of the other indexes lead with. The history list breaks ties by id, so its
-- default page is read straight from this index without a sort.
CREATE INDEX idx_playback_history_stopped_at ON playback_history (stopped_at_ms DESC, id ASC);
