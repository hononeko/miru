-- What a transcode or remux changed, so analytics can show it: the codecs the server converted
-- to, the source and streamed containers, and how subtitles were handled (e.g. 'burn').
ALTER TABLE active_sessions ADD COLUMN transcode_video_codec TEXT;
ALTER TABLE active_sessions ADD COLUMN transcode_audio_codec TEXT;
ALTER TABLE active_sessions ADD COLUMN container TEXT;
ALTER TABLE active_sessions ADD COLUMN transcode_container TEXT;
ALTER TABLE active_sessions ADD COLUMN subtitle_decision TEXT;

ALTER TABLE playback_history ADD COLUMN transcode_video_codec TEXT;
ALTER TABLE playback_history ADD COLUMN transcode_audio_codec TEXT;
ALTER TABLE playback_history ADD COLUMN container TEXT;
ALTER TABLE playback_history ADD COLUMN transcode_container TEXT;
ALTER TABLE playback_history ADD COLUMN subtitle_decision TEXT;
