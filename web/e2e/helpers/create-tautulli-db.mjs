import { Database } from 'bun:sqlite';

const outputPath = process.argv[2];
if (!outputPath) {
	console.error('Usage: bun create-tautulli-db.mjs <output-path>');
	process.exit(1);
}

const db = new Database(outputPath, { create: true });

db.exec(`
CREATE TABLE users (
	user_id TEXT,
	username TEXT,
	friendly_name TEXT,
	email TEXT,
	thumb TEXT,
	is_admin INTEGER
);

CREATE TABLE session_history (
	id INTEGER PRIMARY KEY,
	reference_id INTEGER,
	started INTEGER,
	stopped INTEGER,
	paused_counter INTEGER,
	view_offset INTEGER,
	user_id TEXT,
	user TEXT,
	rating_key TEXT,
	media_type TEXT,
	section_id TEXT,
	player TEXT,
	product TEXT,
	platform TEXT,
	machine_id TEXT,
	ip_address TEXT,
	location TEXT
);

CREATE TABLE session_history_metadata (
	id INTEGER PRIMARY KEY,
	rating_key TEXT,
	media_type TEXT,
	section_id TEXT,
	title TEXT,
	parent_title TEXT,
	grandparent_title TEXT,
	parent_media_index INTEGER,
	media_index INTEGER,
	full_title TEXT,
	year INTEGER,
	thumb TEXT,
	duration INTEGER
);

CREATE TABLE session_history_media_info (
	id INTEGER PRIMARY KEY,
	transcode_decision TEXT,
	video_decision TEXT,
	audio_decision TEXT,
	transcode_hw_decoding INTEGER,
	transcode_hw_encoding INTEGER,
	video_codec TEXT,
	audio_codec TEXT,
	video_resolution TEXT,
	stream_video_resolution TEXT,
	stream_video_codec TEXT,
	stream_audio_codec TEXT,
	bandwidth INTEGER,
	bitrate INTEGER,
	location TEXT
);
`);

const insertUser = db.prepare(`
	INSERT INTO users (user_id, username, friendly_name, email, thumb, is_admin)
	VALUES (?, ?, ?, ?, ?, ?)
`);
insertUser.run('1001', 'alice', 'Alice Admin', 'alice@example.test', '', 1);
insertUser.run('1002', 'bob', 'Bob Viewer', 'bob@example.test', '', 0);

const insertHistory = db.prepare(`
	INSERT INTO session_history (
		id, reference_id, started, stopped, paused_counter, view_offset,
		user_id, user, rating_key, media_type, section_id,
		player, product, platform, machine_id, ip_address, location
	) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
`);

const insertMetadata = db.prepare(`
	INSERT INTO session_history_metadata (
		id, rating_key, media_type, section_id,
		title, parent_title, grandparent_title,
		parent_media_index, media_index, full_title,
		year, thumb, duration
	) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
`);

const insertMediaInfo = db.prepare(`
	INSERT INTO session_history_media_info (
		id, transcode_decision, video_decision, audio_decision,
		transcode_hw_decoding, transcode_hw_encoding,
		video_codec, audio_codec, video_resolution,
		stream_video_resolution, stream_video_codec, stream_audio_codec,
		bandwidth, bitrate, location
	) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
`);

const nowSec = Math.floor(Date.now() / 1000);
const DAY = 86_400;

let nextId = 1;

function addPlay(spec) {
	const id = nextId++;
	const stopped = spec.stoppedSec;
	const started = stopped - spec.watchedSec - (spec.pausedSec ?? 0);
	const userObj = spec.user === 'alice' ? { id: '1001', name: 'alice' } : { id: '1002', name: 'bob' };
	const player =
		spec.user === 'alice'
			? { name: 'Living Room Apple TV', product: 'Plex for Apple TV', platform: 'tvOS', machine: 'appletv-alice', ip: '192.168.1.42', loc: 'lan' }
			: { name: 'Chrome Web', product: 'Plex Web', platform: 'Chrome', machine: 'web-bob', ip: '198.51.100.24', loc: 'wan' };

	insertHistory.run(
		id,
		id,
		started,
		stopped,
		spec.pausedSec ?? 0,
		spec.viewOffsetMs,
		userObj.id,
		userObj.name,
		spec.ratingKey,
		spec.mediaType,
		spec.sectionId,
		player.name,
		player.product,
		player.platform,
		player.machine,
		player.ip,
		player.loc
	);

	insertMetadata.run(
		id,
		spec.ratingKey,
		spec.mediaType,
		spec.sectionId,
		spec.title,
		spec.parentTitle ?? null,
		spec.grandparentTitle ?? null,
		spec.parentIndex ?? null,
		spec.mediaIndex ?? null,
		spec.fullTitle ?? spec.title,
		spec.year,
		`/library/metadata/${spec.ratingKey}/thumb`,
		spec.durationMs
	);

	insertMediaInfo.run(
		id,
		spec.decision,
		spec.videoDecision,
		spec.audioDecision,
		spec.hw ? 1 : 0,
		spec.hw ? 1 : 0,
		spec.videoCodec,
		spec.audioCodec,
		spec.resolution,
		spec.resolution,
		spec.videoCodec,
		spec.audioCodec,
		spec.bandwidthKbps,
		spec.bandwidthKbps,
		player.loc
	);
}

// 6 plays within last 3 days: Spirited Away (direct_play, movie) -> #1 in 7d (21,600s)
for (let i = 0; i < 6; i++) {
	addPlay({
		user: i < 4 ? 'alice' : 'bob',
		stoppedSec: nowSec - 3_600 * (i + 1),
		watchedSec: 3_600,
		pausedSec: 30,
		ratingKey: '101',
		mediaType: 'movie',
		sectionId: '1',
		title: 'Spirited Away',
		year: 2001,
		durationMs: 7_500_000,
		viewOffsetMs: 7_200_000,
		decision: 'direct play',
		videoDecision: 'direct play',
		audioDecision: 'direct play',
		hw: false,
		videoCodec: 'hevc',
		audioCodec: 'eac3',
		resolution: '4k',
		bandwidthKbps: 18_500
	});
}

// 4 plays within last 4 days: Severance S01E01 (HW transcode, episode) -> #2 in 7d (9,600s)
for (let i = 0; i < 4; i++) {
	addPlay({
		user: i < 2 ? 'alice' : 'bob',
		stoppedSec: nowSec - DAY - 7_200 * (i + 1),
		watchedSec: 2_400,
		pausedSec: 15,
		ratingKey: '202',
		mediaType: 'episode',
		sectionId: '2',
		title: 'Good News About Hell',
		parentTitle: 'Season 1',
		grandparentTitle: 'Severance',
		parentIndex: 1,
		mediaIndex: 1,
		fullTitle: 'Severance - Good News About Hell',
		year: 2022,
		durationMs: 3_420_000,
		viewOffsetMs: 3_200_000,
		decision: 'transcode',
		videoDecision: 'transcode',
		audioDecision: 'copy',
		hw: true,
		videoCodec: 'hevc',
		audioCodec: 'eac3',
		resolution: '4k',
		bandwidthKbps: 8_200
	});
}

// 10 plays ~15 days ago: Akira (direct_stream / copy, movie) -> #1 in 30d (50,000s), absent in 7d
for (let i = 0; i < 10; i++) {
	addPlay({
		user: i < 5 ? 'alice' : 'bob',
		stoppedSec: nowSec - 15 * DAY - 3_600 * (i + 1),
		watchedSec: 5_000,
		pausedSec: 0,
		ratingKey: '102',
		mediaType: 'movie',
		sectionId: '1',
		title: 'Akira',
		year: 1988,
		durationMs: 7_440_000,
		viewOffsetMs: 7_000_000,
		decision: 'copy',
		videoDecision: 'copy',
		audioDecision: 'copy',
		hw: false,
		videoCodec: 'h264',
		audioCodec: 'dts',
		resolution: '1080',
		bandwidthKbps: 12_400
	});
}

// 6 plays ~55 days ago: Blade Runner 2049 (direct_play, movie) -> #1 in 90d (57,000s), absent in 30d
for (let i = 0; i < 6; i++) {
	addPlay({
		user: i < 4 ? 'alice' : 'bob',
		stoppedSec: nowSec - 55 * DAY - 3_600 * (i + 1),
		watchedSec: 9_500,
		pausedSec: 60,
		ratingKey: '103',
		mediaType: 'movie',
		sectionId: '1',
		title: 'Blade Runner 2049',
		year: 2017,
		durationMs: 9_840_000,
		viewOffsetMs: 9_600_000,
		decision: 'direct play',
		videoDecision: 'direct play',
		audioDecision: 'direct play',
		hw: false,
		videoCodec: 'hevc',
		audioCodec: 'truehd',
		resolution: '4k',
		bandwidthKbps: 24_000
	});
}

// 4 plays ~140 days ago: Severance S01E02 (SW transcode, episode) -> pushes Severance to #1 in 'all' (81,600s)
for (let i = 0; i < 4; i++) {
	addPlay({
		user: i < 2 ? 'alice' : 'bob',
		stoppedSec: nowSec - 140 * DAY - 3_600 * (i + 1),
		watchedSec: 18_000,
		pausedSec: 0,
		ratingKey: '203',
		mediaType: 'episode',
		sectionId: '2',
		title: 'Half Loop',
		parentTitle: 'Season 1',
		grandparentTitle: 'Severance',
		parentIndex: 1,
		mediaIndex: 2,
		fullTitle: 'Severance - Half Loop',
		year: 2022,
		durationMs: 3_300_000,
		viewOffsetMs: 3_100_000,
		decision: 'transcode',
		videoDecision: 'transcode',
		audioDecision: 'transcode',
		hw: false,
		videoCodec: 'h264',
		audioCodec: 'aac',
		resolution: '1080',
		bandwidthKbps: 6_500
	});
}

db.close();
