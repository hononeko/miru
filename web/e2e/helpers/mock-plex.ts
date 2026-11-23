import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { WebSocketServer, type WebSocket } from 'ws';

// Valid 1x1 transparent PNG
const ONE_BY_ONE_PNG = Buffer.from(
	'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
	'base64'
);

export interface MockPlexSession {
	sessionKey: string;
	sessionId: string;
	ratingKey: string;
	type: 'movie' | 'episode';
	title: string;
	parentTitle?: string;
	grandparentTitle?: string;
	parentIndex?: number;
	index?: number;
	year: number;
	durationMs: number;
	viewOffsetMs: number;
	state: 'playing' | 'paused' | 'stopped' | 'buffering';
	user: {
		id: string;
		title: string;
	};
	player: {
		machineIdentifier: string;
		product: string;
		platform: string;
		device: string;
		title: string;
		address: string;
		local: boolean;
	};
	bandwidthKbps: number;
	location: 'lan' | 'wan';
	decision: 'direct_play' | 'direct_stream' | 'transcode';
	hwTranscode?: boolean;
	videoResolution?: string;
	videoCodec?: string;
	audioCodec?: string;
}

export interface MockPlexOptions {
	token?: string;
	serverName?: string;
	machineId?: string;
	autoAuthorizeAfterPolls?: number;
}

export interface MockSharedServer {
	userId: string;
	allLibraries: boolean;
	sharedSectionKeys?: string[];
}

export interface TerminatedMockSession {
	sessionId: string;
	reason: string;
}

export interface MockPlexServer {
	readonly port: number;
	readonly baseUrl: string;
	readonly token: string;
	readonly serverName: string;
	readonly machineId: string;
	setActiveSessions(sessions: MockPlexSession[]): void;
	setSharedServers(shares: MockSharedServer[]): void;
	getTerminatedSessions(): TerminatedMockSession[];
	clearTerminatedSessions(): void;
	pushPlayingNotification(
		sessionKey: string,
		ratingKey: string,
		state: 'playing' | 'paused' | 'stopped' | 'buffering',
		viewOffsetMs?: number
	): void;
	waitForWsClient(timeoutMs?: number): Promise<void>;
	authorizePin(): void;
	setAutoAuthorizeAfterPolls(polls: number): void;
	stop(): Promise<void>;
}

interface MockMediaItem {
	ratingKey: string;
	type: 'movie' | 'show' | 'season' | 'episode';
	title: string;
	parentTitle?: string;
	grandparentTitle?: string;
	parentRatingKey?: string;
	grandparentRatingKey?: string;
	parentIndex?: number;
	index?: number;
	year: number;
	originallyAvailableAt: string;
	duration?: number;
	contentRating: string;
	studio: string;
	summary: string;
	thumb: string;
	art: string;
	librarySectionID: string;
	librarySectionTitle: string;
	addedAt: number;
	childCount?: number;
	leafCount?: number;
	Genre: Array<{ id: string; tag: string }>;
	Director?: Array<{ id: string; tag: string }>;
	Media?: Array<Record<string, unknown>>;
}

function buildCatalog(): {
	movies: MockMediaItem[];
	shows: MockMediaItem[];
	seasons: MockMediaItem[];
	episodes: MockMediaItem[];
	byKey: Map<string, MockMediaItem>;
} {
	const nowSec = Math.floor(Date.now() / 1000);

	const movies: MockMediaItem[] = [
		{
			ratingKey: '101',
			type: 'movie',
			title: 'Spirited Away',
			year: 2001,
			originallyAvailableAt: '2001-07-20',
			duration: 7_500_000,
			contentRating: 'PG',
			studio: 'Studio Ghibli',
			summary: 'During her family move to the suburbs, a sullen 10-year-old girl wanders into a world ruled by gods, witches, and spirits.',
			thumb: '/library/metadata/101/thumb',
			art: '/library/metadata/101/art',
			librarySectionID: '1',
			librarySectionTitle: 'Movies',
			addedAt: nowSec - 3_600,
			Genre: [
				{ id: '1', tag: 'Animation' },
				{ id: '2', tag: 'Fantasy' }
			],
			Director: [{ id: '10', tag: 'Hayao Miyazaki' }],
			Media: [
				{
					id: 'm-101',
					duration: 7_500_000,
					bitrate: 18_500,
					width: 3840,
					height: 2160,
					videoResolution: '4k',
					videoProfile: 'main 10',
					container: 'mkv',
					videoCodec: 'hevc',
					audioCodec: 'eac3',
					audioChannels: 6,
					selected: true,
					Part: [
						{
							id: 'p-101',
							duration: 7_500_000,
							size: 16_500_000_000,
							container: 'mkv',
							selected: true,
							Stream: [
								{ id: 's-101-v', streamType: 1, codec: 'hevc', profile: 'main 10', width: 3840, height: 2160, selected: true },
								{ id: 's-101-a', streamType: 2, codec: 'eac3', channels: 6, language: 'Japanese', languageCode: 'jpn', selected: true },
								{ id: 's-101-s', streamType: 3, codec: 'srt', language: 'English', languageCode: 'eng', displayTitle: 'English (SRT)' }
							]
						}
					]
				}
			]
		},
		{
			ratingKey: '102',
			type: 'movie',
			title: 'Akira',
			year: 1988,
			originallyAvailableAt: '1988-07-16',
			duration: 7_440_000,
			contentRating: 'R',
			studio: 'Tokyo Movie Shinsha',
			summary: 'A secret military project endangers Neo-Tokyo when it turns a biker gang member into a rampaging psychic psychopath.',
			thumb: '/library/metadata/102/thumb',
			art: '/library/metadata/102/art',
			librarySectionID: '1',
			librarySectionTitle: 'Movies',
			addedAt: nowSec - 86_400,
			Genre: [
				{ id: '1', tag: 'Animation' },
				{ id: '3', tag: 'Sci-Fi' }
			],
			Director: [{ id: '11', tag: 'Katsuhiro Otomo' }],
			Media: [
				{
					id: 'm-102',
					duration: 7_440_000,
					bitrate: 12_400,
					width: 1920,
					height: 1080,
					videoResolution: '1080',
					videoProfile: 'high',
					container: 'mkv',
					videoCodec: 'h264',
					audioCodec: 'dts',
					audioChannels: 6,
					selected: true,
					Part: [
						{
							id: 'p-102',
							duration: 7_440_000,
							size: 11_200_000_000,
							container: 'mkv',
							selected: true,
							Stream: [
								{ id: 's-102-v', streamType: 1, codec: 'h264', profile: 'high', width: 1920, height: 1080, selected: true },
								{ id: 's-102-a', streamType: 2, codec: 'dts', channels: 6, language: 'Japanese', languageCode: 'jpn', selected: true }
							]
						}
					]
				}
			]
		},
		{
			ratingKey: '103',
			type: 'movie',
			title: 'Blade Runner 2049',
			year: 2017,
			originallyAvailableAt: '2017-10-06',
			duration: 9_840_000,
			contentRating: 'R',
			studio: 'Alcon Entertainment',
			summary: 'Young Blade Runner K discovers a long-buried secret that leads him to track down former Blade Runner Rick Deckard.',
			thumb: '/library/metadata/103/thumb',
			art: '/library/metadata/103/art',
			librarySectionID: '1',
			librarySectionTitle: 'Movies',
			addedAt: nowSec - 172_800,
			Genre: [
				{ id: '3', tag: 'Sci-Fi' },
				{ id: '4', tag: 'Thriller' }
			],
			Director: [{ id: '12', tag: 'Denis Villeneuve' }],
			Media: [
				{
					id: 'm-103',
					duration: 9_840_000,
					bitrate: 24_000,
					width: 3840,
					height: 2160,
					videoResolution: '4k',
					videoProfile: 'main 10',
					container: 'mkv',
					videoCodec: 'hevc',
					audioCodec: 'truehd',
					audioChannels: 8,
					selected: true,
					Part: [
						{
							id: 'p-103',
							duration: 9_840_000,
							size: 28_900_000_000,
							container: 'mkv',
							selected: true,
							Stream: [
								{ id: 's-103-v', streamType: 1, codec: 'hevc', profile: 'main 10', width: 3840, height: 2160, selected: true },
								{ id: 's-103-a', streamType: 2, codec: 'truehd', channels: 8, language: 'English', languageCode: 'eng', selected: true }
							]
						}
					]
				}
			]
		}
	];

	const shows: MockMediaItem[] = [
		{
			ratingKey: '200',
			type: 'show',
			title: 'Severance',
			year: 2022,
			originallyAvailableAt: '2022-02-18',
			contentRating: 'TV-MA',
			studio: 'Apple TV+',
			summary: 'Mark leads a team of office workers whose memories have been surgically divided between their work and personal lives.',
			thumb: '/library/metadata/200/thumb',
			art: '/library/metadata/200/art',
			librarySectionID: '2',
			librarySectionTitle: 'TV Shows',
			addedAt: nowSec - 200_000,
			childCount: 1,
			leafCount: 2,
			Genre: [
				{ id: '3', tag: 'Sci-Fi' },
				{ id: '5', tag: 'Drama' }
			]
		}
	];

	const seasons: MockMediaItem[] = [
		{
			ratingKey: '201',
			type: 'season',
			title: 'Season 1',
			parentTitle: 'Severance',
			parentRatingKey: '200',
			index: 1,
			year: 2022,
			originallyAvailableAt: '2022-02-18',
			contentRating: 'TV-MA',
			studio: 'Apple TV+',
			summary: 'Macrodata Refinement welcomes a new colleague as cracks appear in Lumon Industries.',
			thumb: '/library/metadata/201/thumb',
			art: '/library/metadata/200/art',
			librarySectionID: '2',
			librarySectionTitle: 'TV Shows',
			addedAt: nowSec - 190_000,
			leafCount: 2,
			Genre: [{ id: '5', tag: 'Drama' }]
		}
	];

	const episodes: MockMediaItem[] = [
		{
			ratingKey: '202',
			type: 'episode',
			title: 'Good News About Hell',
			parentTitle: 'Season 1',
			grandparentTitle: 'Severance',
			parentRatingKey: '201',
			grandparentRatingKey: '200',
			parentIndex: 1,
			index: 1,
			year: 2022,
			originallyAvailableAt: '2022-02-18',
			duration: 3_420_000,
			contentRating: 'TV-MA',
			studio: 'Apple TV+',
			summary: 'Mark is promoted to lead a team of employees who have had their memories surgically divided.',
			thumb: '/library/metadata/202/thumb',
			art: '/library/metadata/200/art',
			librarySectionID: '2',
			librarySectionTitle: 'TV Shows',
			addedAt: nowSec - 7_200,
			Genre: [{ id: '5', tag: 'Drama' }],
			Media: [
				{
					id: 'm-202',
					duration: 3_420_000,
					bitrate: 14_000,
					width: 3840,
					height: 2160,
					videoResolution: '4k',
					videoProfile: 'main 10',
					container: 'mkv',
					videoCodec: 'hevc',
					audioCodec: 'eac3',
					audioChannels: 6,
					selected: true,
					Part: [
						{
							id: 'p-202',
							duration: 3_420_000,
							size: 5_900_000_000,
							container: 'mkv',
							selected: true,
							Stream: [
								{ id: 's-202-v', streamType: 1, codec: 'hevc', width: 3840, height: 2160, selected: true },
								{ id: 's-202-a', streamType: 2, codec: 'eac3', channels: 6, language: 'English', languageCode: 'eng', selected: true }
							]
						}
					]
				}
			]
		},
		{
			ratingKey: '203',
			type: 'episode',
			title: 'Half Loop',
			parentTitle: 'Season 1',
			grandparentTitle: 'Severance',
			parentRatingKey: '201',
			grandparentRatingKey: '200',
			parentIndex: 1,
			index: 2,
			year: 2022,
			originallyAvailableAt: '2022-02-18',
			duration: 3_300_000,
			contentRating: 'TV-MA',
			studio: 'Apple TV+',
			summary: 'The team trains new hire Helly on macrodata refinement while Mark takes a day off.',
			thumb: '/library/metadata/203/thumb',
			art: '/library/metadata/200/art',
			librarySectionID: '2',
			librarySectionTitle: 'TV Shows',
			addedAt: nowSec - 14_400,
			Genre: [{ id: '5', tag: 'Drama' }],
			Media: [
				{
					id: 'm-203',
					duration: 3_300_000,
					bitrate: 9_800,
					width: 1920,
					height: 1080,
					videoResolution: '1080',
					videoProfile: 'high',
					container: 'mkv',
					videoCodec: 'h264',
					audioCodec: 'aac',
					audioChannels: 2,
					selected: true,
					Part: [
						{
							id: 'p-203',
							duration: 3_300_000,
							size: 3_800_000_000,
							container: 'mkv',
							selected: true,
							Stream: [
								{ id: 's-203-v', streamType: 1, codec: 'h264', width: 1920, height: 1080, selected: true },
								{ id: 's-203-a', streamType: 2, codec: 'aac', channels: 2, language: 'English', languageCode: 'eng', selected: true }
							]
						}
					]
				}
			]
		}
	];

	const byKey = new Map<string, MockMediaItem>();
	for (const item of [...movies, ...shows, ...seasons, ...episodes]) {
		byKey.set(item.ratingKey, item);
	}

	return { movies, shows, seasons, episodes, byKey };
}

function sessionToPlexMetadata(s: MockPlexSession): Record<string, unknown> {
	const partDecision =
		s.decision === 'direct_play'
			? 'directplay'
			: s.decision === 'direct_stream'
				? 'copy'
				: 'transcode';

	const base: Record<string, unknown> = {
		ratingKey: s.ratingKey,
		sessionKey: s.sessionKey,
		type: s.type,
		title: s.title,
		parentTitle: s.parentTitle,
		grandparentTitle: s.grandparentTitle,
		parentIndex: s.parentIndex,
		index: s.index,
		year: s.year,
		duration: s.durationMs,
		viewOffset: s.viewOffsetMs,
		thumb: `/library/metadata/${s.ratingKey}/thumb`,
		librarySectionID: s.type === 'movie' ? '1' : '2',
		User: {
			id: s.user.id,
			title: s.user.title,
			thumb: ''
		},
		Player: {
			machineIdentifier: s.player.machineIdentifier,
			product: s.player.product,
			platform: s.player.platform,
			device: s.player.device,
			title: s.player.title,
			address: s.player.address,
			local: s.player.local,
			state: s.state
		},
		Session: {
			id: s.sessionId,
			bandwidth: s.bandwidthKbps,
			location: s.location
		},
		Media: [
			{
				id: `media-${s.ratingKey}`,
				duration: s.durationMs,
				bitrate: s.bandwidthKbps,
				videoResolution: s.videoResolution ?? '4k',
				container: 'mkv',
				videoCodec: s.videoCodec ?? 'hevc',
				audioCodec: s.audioCodec ?? 'eac3',
				selected: true,
				Part: [
					{
						id: `part-${s.ratingKey}`,
						duration: s.durationMs,
						container: 'mkv',
						decision: partDecision,
						selected: true,
						Stream: [
							{
								id: `v-${s.ratingKey}`,
								streamType: 1,
								codec: s.videoCodec ?? 'hevc',
								decision: s.decision === 'transcode' ? 'transcode' : partDecision,
								selected: true
							},
							{
								id: `a-${s.ratingKey}`,
								streamType: 2,
								codec: s.audioCodec ?? 'eac3',
								decision: s.decision === 'direct_play' ? 'directplay' : 'copy',
								selected: true
							}
						]
					}
				]
			}
		]
	};

	if (s.decision === 'transcode') {
		base.TranscodeSession = {
			videoDecision: 'transcode',
			audioDecision: 'copy',
			container: 'mpegts',
			videoCodec: 'h264',
			audioCodec: 'aac',
			transcodeHwRequested: Boolean(s.hwTranscode),
			transcodeHwFullPipeline: Boolean(s.hwTranscode),
			transcodeHwDecoding: s.hwTranscode ? 'vaapi' : undefined,
			transcodeHwEncoding: s.hwTranscode ? 'vaapi' : undefined,
			progress: 35.0,
			speed: 2.8
		};
	}

	return base;
}

export async function startMockPlexServer(options: MockPlexOptions = {}): Promise<MockPlexServer> {
	const token = options.token ?? 'e2e-plex-secret-token';
	const serverName = options.serverName ?? 'Miru Lab Plex';
	const machineId = options.machineId ?? 'miru-e2e-machine-001';
	const catalog = buildCatalog();
	const mockSections = [
		{ key: '1', title: 'Movies', type: 'movie', uuid: 'sec-movies-1', count: catalog.movies.length },
		{ key: '2', title: 'TV Shows', type: 'show', uuid: 'sec-shows-2', count: catalog.shows.length }
	];

	let activeSessions: Array<MockPlexSession & { _removeAfterFetch?: boolean }> = [];
	const terminatedSessions: TerminatedMockSession[] = [];
	let sharedServers: MockSharedServer[] = [
		{ userId: '1002', allLibraries: false, sharedSectionKeys: ['1'] },
		{ userId: '1003', allLibraries: true }
	];
	const wsClients = new Set<WebSocket>();
	const wsWaiters: Array<() => void> = [];

	// PIN OAuth state
	let nextPinId = 9001;
	let autoAuthorizeAfterPolls = options.autoAuthorizeAfterPolls ?? 1;
	const pins = new Map<
		number,
		{ id: number; code: string; clientIdentifier: string; pollCount: number; authorized: boolean }
	>();

	const sendJson = (res: http.ServerResponse, status: number, body: unknown) => {
		const payload = JSON.stringify(body);
		res.writeHead(status, {
			'Content-Type': 'application/json',
			'Content-Length': Buffer.byteLength(payload)
		});
		res.end(payload);
	};

	let port = 0;
	let baseUrl = '';

	const handleHttpRequest = (req: http.IncomingMessage, res: http.ServerResponse) => {
		const url = new URL(req.url ?? '/', `http://127.0.0.1:${port}`);
		const pathname = url.pathname;
		const method = req.method ?? 'GET';

		// Plex.tv OAuth PIN endpoints (no X-Plex-Token required for PIN creation/polling)
		if (method === 'POST' && pathname === '/api/v2/pins') {
			const id = nextPinId++;
			const code = `E2EPIN${id}`;
			const clientIdentifier = String(req.headers['x-plex-client-identifier'] ?? 'miru-e2e');
			pins.set(id, { id, code, clientIdentifier, pollCount: 0, authorized: false });
			sendJson(res, 201, {
				id,
				code,
				clientIdentifier,
				expiresIn: 900,
				authToken: null
			});
			return;
		}

		const pinMatch = pathname.match(/^\/api\/v2\/pins\/(\d+)$/);
		if (method === 'GET' && pinMatch) {
			const pinId = Number(pinMatch[1]);
			const pin = pins.get(pinId);
			if (!pin) {
				sendJson(res, 404, { error: 'PIN not found' });
				return;
			}
			pin.pollCount += 1;
			if (autoAuthorizeAfterPolls > 0 && pin.pollCount >= autoAuthorizeAfterPolls) {
				pin.authorized = true;
			}
			sendJson(res, 200, {
				id: pin.id,
				code: pin.code,
				clientIdentifier: pin.clientIdentifier,
				expiresIn: 880,
				authToken: pin.authorized ? token : null
			});
			return;
		}

		// Token check for authenticated endpoints
		const reqToken = req.headers['x-plex-token'] ?? url.searchParams.get('X-Plex-Token');
		if (reqToken !== token) {
			sendJson(res, 401, { error: 'Unauthorized mock Plex token' });
			return;
		}

		// Plex.tv discovery & user enrichment
		if (pathname === '/api/v2/resources') {
			sendJson(res, 200, [
				{
					name: serverName,
					product: 'Plex Media Server',
					productVersion: '1.41.0.8994',
					platform: 'Linux',
					clientIdentifier: machineId,
					provides: 'server',
					owned: true,
					accessToken: token,
					presence: true,
					connections: [
						{
							protocol: 'http',
							address: '127.0.0.1',
							port,
							uri: baseUrl,
							local: true,
							relay: false,
							IPv6: false
						}
					]
				}
			]);
			return;
		}

		if (pathname === '/api/v2/user') {
			sendJson(res, 200, {
				id: 1001,
				uuid: 'uuid-alice-1001',
				username: 'alice',
				title: 'Alice Admin',
				email: 'alice@example.test',
				thumb: ''
			});
			return;
		}

		if (pathname === '/api/v2/friends') {
			sendJson(res, 200, [
				{
					id: 1002,
					uuid: 'uuid-bob-1002',
					username: 'bob',
					title: 'Bob Viewer',
					email: 'bob@example.test',
					thumb: ''
				},
				{
					id: 1003,
					uuid: 'uuid-carol-1003',
					username: 'carol',
					title: 'Carol Guest',
					email: 'carol@example.test',
					thumb: ''
				}
			]);
			return;
		}

		if (pathname === `/api/servers/${machineId}/shared_servers`) {
			const sharedXml = sharedServers
				.map((s) => {
					const sectionsXml = s.allLibraries
						? ''
						: mockSections
								.map((sec) => {
									const isShared = s.sharedSectionKeys?.includes(sec.key) ? '1' : '0';
									return `<Section id="${sec.key}" key="${sec.key}" title="${sec.title}" type="${sec.type}" shared="${isShared}"/>`;
								})
								.join('');
					return `<SharedServer userID="${s.userId}" allLibraries="${s.allLibraries ? '1' : '0'}" shared="1">${sectionsXml}</SharedServer>`;
				})
				.join('');
			const xmlPayload = `<?xml version="1.0" encoding="UTF-8"?><MediaContainer size="${sharedServers.length}">${sharedXml}</MediaContainer>`;
			res.writeHead(200, {
				'Content-Type': 'application/xml; charset=utf-8',
				'Content-Length': Buffer.byteLength(xmlPayload)
			});
			res.end(xmlPayload);
			return;
		}

		// PMS root identity
		if (pathname === '/') {
			sendJson(res, 200, {
				MediaContainer: {
					machineIdentifier: machineId,
					friendlyName: serverName,
					version: '1.41.0.8994',
					platform: 'Linux',
					claimed: true
				}
			});
			return;
		}

		if (pathname === '/status/sessions') {
			const metadata = activeSessions.map(sessionToPlexMetadata);
			activeSessions = activeSessions.filter((s) => !s._removeAfterFetch);
			sendJson(res, 200, {
				MediaContainer: {
					size: metadata.length,
					Metadata: metadata
				}
			});
			return;
		}

		if (pathname === '/status/sessions/terminate') {
			const sessionId = (url.searchParams.get('sessionId') ?? '').trim();
			const reason = url.searchParams.get('reason') ?? '';
			const matchIndex = activeSessions.findIndex(
				(s) => s.sessionId === sessionId || s.sessionKey === sessionId
			);
			if (matchIndex < 0) {
				sendJson(res, 404, { error: `Session '${sessionId}' not found` });
				return;
			}
			const existing = activeSessions[matchIndex];
			activeSessions[matchIndex] = { ...existing, state: 'stopped', _removeAfterFetch: true };
			terminatedSessions.push({ sessionId, reason });
			sendJson(res, 200, {});

			setTimeout(() => {
				const frame = JSON.stringify({
					NotificationContainer: {
						type: 'playing',
						size: 1,
						PlaySessionStateNotification: [
							{
								sessionKey: existing.sessionKey,
								ratingKey: existing.ratingKey,
								state: 'stopped',
								viewOffset: existing.viewOffsetMs
							}
						]
					}
				});
				for (const client of wsClients) {
					if (client.readyState === 1) {
						client.send(frame);
					}
				}
			}, 20);
			return;
		}

		if (pathname === '/accounts') {
			sendJson(res, 200, {
				MediaContainer: {
					size: 3,
					Account: [
						{ id: 1, name: 'alice', thumb: '' },
						{ id: 1002, name: 'bob', thumb: '' },
						{ id: 1003, name: 'carol', thumb: '' }
					]
				}
			});
			return;
		}

		if (pathname === '/library/sections') {
			sendJson(res, 200, {
				MediaContainer: {
					size: mockSections.length,
					Directory: mockSections
				}
			});
			return;
		}

		const sectionAllMatch = pathname.match(/^\/library\/sections\/([^/]+)\/all$/);
		if (sectionAllMatch) {
			const sectionKey = sectionAllMatch[1];
			const start = Number(url.searchParams.get('X-Plex-Container-Start') ?? '0');
			const sizeParam = url.searchParams.get('X-Plex-Container-Size');
			const size = sizeParam !== null ? Number(sizeParam) : 200;
			const typeCode = url.searchParams.get('type');

			let items: MockMediaItem[] = [];
			if (sectionKey === '1') {
				items = catalog.movies;
			} else if (sectionKey === '2') {
				if (typeCode === '2') {
					items = catalog.shows;
				} else if (typeCode === '3') {
					items = catalog.seasons;
				} else {
					items = catalog.episodes;
				}
			} else {
				sendJson(res, 404, { error: 'Unknown section' });
				return;
			}

			const sliced = size === 0 ? [] : items.slice(start, start + size);
			sendJson(res, 200, {
				MediaContainer: {
					size: sliced.length,
					totalSize: items.length,
					offset: start,
					Metadata: sliced
				}
			});
			return;
		}

		const metadataMatch = pathname.match(/^\/library\/metadata\/([^/]+)$/);
		if (metadataMatch) {
			const item = catalog.byKey.get(metadataMatch[1]);
			if (!item) {
				sendJson(res, 404, { error: 'Media item not found' });
				return;
			}
			sendJson(res, 200, {
				MediaContainer: {
					size: 1,
					Metadata: [item]
				}
			});
			return;
		}

		if (
			pathname === '/photo/:/transcode' ||
			/^\/library\/metadata\/[^/]+\/(thumb|art)$/.test(pathname)
		) {
			res.writeHead(200, {
				'Content-Type': 'image/png',
				'Content-Length': ONE_BY_ONE_PNG.length
			});
			res.end(ONE_BY_ONE_PNG);
			return;
		}

		sendJson(res, 404, { error: `Unhandled mock Plex path: ${pathname}` });
	};

	const server = http.createServer(handleHttpRequest);

	const wss = new WebSocketServer({ noServer: true });
	server.on('upgrade', (req, socket, head) => {
		const upgradeHeader = String(req.headers.upgrade ?? '').toLowerCase();
		// Java HttpClient sends `Upgrade: h2c` on cleartext HTTP requests by default.
		// Because an `upgrade` listener is attached for WebSockets, Node routes those
		// requests here; respond with standard HTTP/1.1 instead of dropping the socket.
		if (upgradeHeader !== 'websocket') {
			if (head.length > 0) {
				req.unshift(head);
			}
			const res = new http.ServerResponse(req);
			res.assignSocket(socket as unknown as import('node:net').Socket);
			res.setHeader('Connection', 'close');
			res.on('finish', () => {
				res.detachSocket(socket as unknown as import('node:net').Socket);
				socket.end();
			});
			handleHttpRequest(req, res);
			return;
		}

		const url = new URL(req.url ?? '/', `http://127.0.0.1:${port}`);
		if (url.pathname !== '/:/websockets/notifications') {
			socket.destroy();
			return;
		}
		const reqToken = req.headers['x-plex-token'] ?? url.searchParams.get('X-Plex-Token');
		if (reqToken !== token) {
			socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
			socket.destroy();
			return;
		}
		wss.handleUpgrade(req, socket, head, (ws) => {
			wsClients.add(ws);
			ws.on('close', () => {
				wsClients.delete(ws);
			});
			while (wsWaiters.length > 0) {
				wsWaiters.shift()?.();
			}
		});
	});

	await new Promise<void>((resolve, reject) => {
		server.once('error', reject);
		server.listen(0, '127.0.0.1', () => {
			const addr = server.address() as AddressInfo;
			port = addr.port;
			baseUrl = `http://127.0.0.1:${port}`;
			resolve();
		});
	});

	return {
		get port() {
			return port;
		},
		get baseUrl() {
			return baseUrl;
		},
		token,
		serverName,
		machineId,
		setActiveSessions(sessions: MockPlexSession[]) {
			activeSessions = [...sessions];
		},
		setSharedServers(shares: MockSharedServer[]) {
			sharedServers = shares.map((s) => ({
				...s,
				sharedSectionKeys: s.sharedSectionKeys ? [...s.sharedSectionKeys] : undefined
			}));
		},
		getTerminatedSessions() {
			return terminatedSessions.map((t) => ({ ...t }));
		},
		clearTerminatedSessions() {
			terminatedSessions.length = 0;
		},
		pushPlayingNotification(
			sessionKey: string,
			ratingKey: string,
			state: 'playing' | 'paused' | 'stopped' | 'buffering',
			viewOffsetMs = 60_000
		) {
			const frame = JSON.stringify({
				NotificationContainer: {
					type: 'playing',
					size: 1,
					PlaySessionStateNotification: [
						{
							sessionKey,
							ratingKey,
							state,
							viewOffset: viewOffsetMs
						}
					]
				}
			});
			for (const client of wsClients) {
				if (client.readyState === 1) {
					client.send(frame);
				}
			}
		},
		waitForWsClient(timeoutMs = 15_000): Promise<void> {
			if (wsClients.size > 0) return Promise.resolve();
			return new Promise<void>((resolve, reject) => {
				const timer = setTimeout(() => {
					reject(new Error(`Timed out after ${timeoutMs}ms waiting for Plex WebSocket client`));
				}, timeoutMs);
				wsWaiters.push(() => {
					clearTimeout(timer);
					resolve();
				});
			});
		},
		authorizePin() {
			for (const pin of pins.values()) {
				pin.authorized = true;
			}
		},
		setAutoAuthorizeAfterPolls(polls: number) {
			autoAuthorizeAfterPolls = polls;
		},
		async stop() {
			for (const client of wsClients) {
				client.terminate();
			}
			wsClients.clear();
			await new Promise<void>((resolve) => wss.close(() => resolve()));
			await new Promise<void>((resolve) => server.close(() => resolve()));
		}
	};
}
