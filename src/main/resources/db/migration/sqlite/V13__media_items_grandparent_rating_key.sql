-- Rating key of the show an episode belongs to, or of the artist of a track. Set by the next deep library scan.
ALTER TABLE media_items_cache ADD COLUMN grandparent_rating_key TEXT;
