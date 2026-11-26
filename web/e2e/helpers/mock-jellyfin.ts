import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { WebSocketServer, type WebSocket } from 'ws';

// Valid 1x1 transparent PNG
const ONE_BY_ONE_PNG = Buffer.from(
	'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
	'base64'
);

const TERMINATION_DELAY_MS = 350;

export interface MockJellyfinSession {
	sessionId: string;
	itemId: string;
	type: 'Movie' | 'Episode';
	title: string;
	seriesId?: string;
	seriesName?: string;
	seasonId?: string;
	seasonName?: string;
	parentIndexNumber?: number;
	indexNumber?: number;
	year: number;
	durationMs: number;
	positionMs: number;
	paused: boolean;
	userId: string;
	userName: string;
	client: string;
	deviceName: string;
	deviceId: string;
	remoteEndPoint: string;
	playMethod: 'DirectPlay' | 'DirectStream' | 'Transcode';
	hwAccel?: string;
	bitrateKbps: number;
	videoWidth?: number;
	videoHeight?: number;
	videoCodec?: string;
	audioCodec?: string;
}

export interface MockJellyfinOptions {
	apiKey?: string;
	serverName?: string;
	machineId?: string;
	quickConnectEnabled?: boolean;
}

export interface TerminatedJellyfinSession {
	sessionId: string;
	message: string;
}

export interface MockJellyfinServer {
	readonly port: number;
	readonly baseUrl: string;
	readonly apiKey: string;
	readonly serverName: string;
	readonly machineId: string;
	setActiveSessions(sessions: MockJellyfinSession[]): void;
	pushSessionsUpdate(): void;
	getTerminatedSessions(): TerminatedJellyfinSession[];
	clearTerminatedSessions(): void;
	getSessionsStartCount(): number;
	getKeepAliveCount(): number;
	approveQuickConnect(options?: { isAdministrator?: boolean; userName?: string }): void;
	expireQuickConnect(): void;
	setQuickConnectEnabled(enabled: boolean): void;
	getIssuedApiKeys(): string[];
	getRevokedApiKeys(): string[];
	getLogoutCount(): number;
	waitForWsClient(timeoutMs?: number): Promise<void>;
	stop(): Promise<void>;
}

const MS_TO_TICKS = 10_000;

interface MockJfItem {
	Id: string;
	Name: string;
	Type: 'Movie' | 'Series' | 'Season' | 'Episode';
	ParentId: string;
	SeriesId?: string;
	SeriesName?: string;
	SeasonId?: string;
	SeasonName?: string;
	ParentIndexNumber?: number;
	IndexNumber?: number;
	ProductionYear: number;
	PremiereDate: string;
	DateCreated: string;
	RunTimeTicks?: number;
	OfficialRating: string;
	Overview: string;
	Genres: string[];
	Studios: Array<{ Name: string }>;
	People?: Array<{ Name: string; Type: string }>;
	ImageTags: Record<string, string>;
	BackdropImageTags?: string[];
	ChildCount?: number;
	RecursiveItemCount?: number;
	MediaSources?: Array<Record<string, unknown>>;
	libraryId: string;
	libraryName: string;
}

function buildJellyfinCatalog(): {
	movies: MockJfItem[];
	shows: MockJfItem[];
	seasons: MockJfItem[];
	episodes: MockJfItem[];
	byId: Map<string, MockJfItem>;
} {
	const nowIso = new Date(Date.now() - 3_600_000).toISOString();
	const movies: MockJfItem[] = [
		{
			Id: 'jf-m1',
			Name: 'Arrival',
			Type: 'Movie',
			ParentId: 'jf-lib-movies',
			ProductionYear: 2016,
			PremiereDate: '2016-11-11T00:00:00Z',
			DateCreated: nowIso,
			RunTimeTicks: 6_960_000 * MS_TO_TICKS,
			OfficialRating: 'PG-13',
			Overview: 'A linguist works with the military to communicate with alien lifeforms after twelve mysterious spacecraft appear around the world.',
			Genres: ['Sci-Fi', 'Drama'],
			Studios: [{ Name: 'Paramount Pictures' }],
			People: [{ Name: 'Denis Villeneuve', Type: 'Director' }],
			ImageTags: { Primary: 'jf-p1' },
			BackdropImageTags: ['jf-b1'],
			MediaSources: [
				{
					Id: 'jf-ms-1',
					Container: 'mkv',
					Bitrate: 22_000_000,
					Size: 19_200_000_000,
					MediaStreams: [
						{ Type: 'Video', Index: 0, Codec: 'hevc', Profile: 'Main 10', Width: 3840, Height: 2160, IsDefault: true },
						{ Type: 'Audio', Index: 1, Codec: 'truehd', Channels: 8, Language: 'eng', IsDefault: true },
						{ Type: 'Subtitle', Index: 2, Language: 'eng', DisplayTitle: 'English' }
					]
				}
			],
			libraryId: 'jf-lib-movies',
			libraryName: 'Jellyfin Movies'
		}
	];

	const shows: MockJfItem[] = [
		{
			Id: 'jf-show1',
			Name: 'The Expanse',
			Type: 'Series',
			ParentId: 'jf-lib-shows',
			ProductionYear: 2015,
			PremiereDate: '2015-12-14T00:00:00Z',
			DateCreated: nowIso,
			OfficialRating: 'TV-14',
			Overview: 'A detective and a ship officer unravel a conspiracy that threatens the solar system.',
			Genres: ['Sci-Fi', 'Thriller'],
			Studios: [{ Name: 'Alcon Television' }],
			ImageTags: { Primary: 'jf-sp1' },
			BackdropImageTags: ['jf-sb1'],
			ChildCount: 1,
			RecursiveItemCount: 1,
			libraryId: 'jf-lib-shows',
			libraryName: 'Jellyfin Series'
		}
	];

	const seasons: MockJfItem[] = [
		{
			Id: 'jf-season1',
			Name: 'Season 1',
			Type: 'Season',
			ParentId: 'jf-show1',
			SeriesId: 'jf-show1',
			SeriesName: 'The Expanse',
			IndexNumber: 1,
			ProductionYear: 2015,
			PremiereDate: '2015-12-14T00:00:00Z',
			DateCreated: nowIso,
			OfficialRating: 'TV-14',
			Overview: 'Season 1 of The Expanse.',
			Genres: ['Sci-Fi'],
			Studios: [{ Name: 'Alcon Television' }],
			ImageTags: { Primary: 'jf-sn1' },
			RecursiveItemCount: 1,
			libraryId: 'jf-lib-shows',
			libraryName: 'Jellyfin Series'
		}
	];

	const episodes: MockJfItem[] = [
		{
			Id: 'jf-ep1',
			Name: 'Dulcinea',
			Type: 'Episode',
			ParentId: 'jf-season1',
			SeriesId: 'jf-show1',
			SeriesName: 'The Expanse',
			SeasonId: 'jf-season1',
			SeasonName: 'Season 1',
			ParentIndexNumber: 1,
			IndexNumber: 1,
			ProductionYear: 2015,
			PremiereDate: '2015-12-14T00:00:00Z',
			DateCreated: nowIso,
			RunTimeTicks: 2_820_000 * MS_TO_TICKS,
			OfficialRating: 'TV-14',
			Overview: 'Detective Miller investigates the disappearance of Julie Mao.',
			Genres: ['Sci-Fi'],
			Studios: [{ Name: 'Alcon Television' }],
			ImageTags: { Primary: 'jf-ep-p1' },
			MediaSources: [
				{
					Id: 'jf-ms-ep1',
					Container: 'mkv',
					Bitrate: 9_500_000,
					Size: 3_400_000_000,
					MediaStreams: [
						{ Type: 'Video', Index: 0, Codec: 'h264', Width: 1920, Height: 1080, IsDefault: true },
						{ Type: 'Audio', Index: 1, Codec: 'aac', Channels: 6, IsDefault: true }
					]
				}
			],
			libraryId: 'jf-lib-shows',
			libraryName: 'Jellyfin Series'
		}
	];

	const byId = new Map<string, MockJfItem>();
	for (const item of [...movies, ...shows, ...seasons, ...episodes]) {
		byId.set(item.Id, item);
	}
	return { movies, shows, seasons, episodes, byId };
}

function toJellyfinSessionDto(s: MockJellyfinSession): Record<string, unknown> {
	const isTranscode = s.playMethod === 'Transcode';
	const width = s.videoWidth ?? 3840;
	const height = s.videoHeight ?? 2160;
	return {
		Id: s.sessionId,
		UserId: s.userId,
		UserName: s.userName,
		UserPrimaryImageTag: 'av-tag',
		Client: s.client,
		DeviceName: s.deviceName,
		DeviceId: s.deviceId,
		RemoteEndPoint: s.remoteEndPoint,
		PlayState: {
			PositionTicks: s.positionMs * MS_TO_TICKS,
			IsPaused: s.paused,
			PlayMethod: s.playMethod,
			MediaSourceId: `ms-${s.itemId}`,
			AudioStreamIndex: 1
		},
		...(isTranscode
			? {
					TranscodingInfo: {
						VideoCodec: 'h264',
						AudioCodec: 'aac',
						Container: 'ts',
						IsVideoDirect: false,
						IsAudioDirect: false,
						Bitrate: s.bitrateKbps * 1_000,
						CompletionPercentage: 40.0,
						HardwareAccelerationType: s.hwAccel ?? 'vaapi'
					}
				}
			: {}),
		NowPlayingItem: {
			Id: s.itemId,
			Name: s.title,
			Type: s.type,
			SeriesId: s.seriesId,
			SeriesName: s.seriesName,
			SeasonId: s.seasonId,
			SeasonName: s.seasonName,
			ParentIndexNumber: s.parentIndexNumber,
			IndexNumber: s.indexNumber,
			ProductionYear: s.year,
			RunTimeTicks: s.durationMs * MS_TO_TICKS,
			ImageTags: { Primary: 'p-tag' },
			BackdropImageTags: ['b-tag'],
			MediaSources: [
				{
					Id: `ms-${s.itemId}`,
					Container: 'mkv',
					Bitrate: s.bitrateKbps * 1_000,
					MediaStreams: [
						{ Type: 'Video', Index: 0, Codec: s.videoCodec ?? 'hevc', Width: width, Height: height, IsDefault: true },
						{ Type: 'Audio', Index: 1, Codec: s.audioCodec ?? 'truehd', IsDefault: true }
					]
				}
			]
		}
	};
}

function sendJson(res: http.ServerResponse, status: number, payload: unknown): void {
	const body = JSON.stringify(payload);
	res.writeHead(status, {
		'Content-Type': 'application/json; charset=utf-8',
		'Content-Length': Buffer.byteLength(body)
	});
	res.end(body);
}

function readBody(req: http.IncomingMessage): Promise<string> {
	return new Promise((resolve, reject) => {
		const chunks: Buffer[] = [];
		req.on('data', (c: Buffer) => chunks.push(c));
		req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
		req.on('error', reject);
	});
}

export async function startMockJellyfinServer(options: MockJellyfinOptions = {}): Promise<MockJellyfinServer> {
	const apiKey = options.apiKey ?? 'e2e-jellyfin-api-key-98765';
	const serverName = options.serverName ?? 'Studio Jellyfin';
	const machineId = options.machineId ?? 'e2e-jellyfin-machine-001';
	const catalog = buildJellyfinCatalog();

	let port = 0;
	let baseUrl = '';
	let activeSessions: MockJellyfinSession[] = [];
	const pendingMessages = new Map<string, string>();
	const terminatedSessions: TerminatedJellyfinSession[] = [];
	const wsClients = new Set<WebSocket>();
	const wsWaiters: Array<() => void> = [];
	let stopped = false;
	let sessionsStartCount = 0;
	let keepAliveCount = 0;
	let quickConnectEnabled = options.quickConnectEnabled ?? true;
	let nextQcCounter = 1;
	let nextKeyCounter = 1;
	let logoutCount = 0;
	const validApiKeys = new Set<string>([apiKey]);
	// Jellyfin 10.11 lists API keys with Id 0 and IsActive false for every key (AuthenticationManager.GetApiKeys)
	// and can send DateCreated without an offset; the mock does the same so Miru can't rely on either
	const jellyfinApiKeyDate = (ms: number) => new Date(ms).toISOString().replace('Z', '');
	const issuedApiKeys: string[] = [];
	const revokedApiKeys: string[] = [];
	const authKeyRecords: Array<{
		Id: number;
		AccessToken: string;
		AppName: string;
		IsActive: boolean;
		DateCreated: string;
	}> = [
		{
			Id: 0,
			AccessToken: 'older-miru-key-should-not-be-picked',
			AppName: 'Miru',
			IsActive: false,
			DateCreated: jellyfinApiKeyDate(Date.now() - 3_600_000)
		}
	];
	const qcRequests = new Map<
		string,
		{ secret: string; code: string; authenticated: boolean; expired: boolean; isAdministrator: boolean; userName: string }
	>();
	const tempSessions = new Map<string, { isAdministrator: boolean; userName: string }>();

	const broadcastSessions = () => {
		const payload = JSON.stringify({
			MessageType: 'Sessions',
			Data: activeSessions.map(toJellyfinSessionDto)
		});
		for (const ws of wsClients) {
			if (ws.readyState === ws.OPEN) {
				ws.send(payload);
			}
		}
	};

	const extractToken = (req: http.IncomingMessage): string | null => {
		const auth = String(req.headers.authorization ?? '');
		if (!auth.startsWith('MediaBrowser ')) return null;
		const match = auth.match(/Token="([^"]+)"/);
		return match ? match[1] : null;
	};

	const isAuthorized = (req: http.IncomingMessage): boolean => {
		const tok = extractToken(req);
		return tok !== null && validApiKeys.has(tok);
	};

	const handleHttpRequest = async (req: http.IncomingMessage, res: http.ServerResponse, preReadBody?: string) => {
		const url = new URL(req.url ?? '/', `http://127.0.0.1:${port}`);
		const pathname = url.pathname;
		const method = req.method ?? 'GET';

		if (method === 'GET' && pathname === '/System/Info/Public') {
			sendJson(res, 200, {
				Id: machineId,
				ServerName: serverName,
				Version: '10.10.3',
				OperatingSystem: 'Linux'
			});
			return;
		}

		if (method === 'GET' && pathname === '/QuickConnect/Enabled') {
			sendJson(res, 200, quickConnectEnabled);
			return;
		}

		if (method === 'POST' && pathname === '/QuickConnect/Initiate') {
			const auth = String(req.headers.authorization ?? '');
			if (!auth.startsWith('MediaBrowser ')) {
				sendJson(res, 401, { error: 'Missing MediaBrowser header' });
				return;
			}
			const idx = nextQcCounter++;
			const secret = `e2e-qc-secret-${idx}`;
			const code = String(100000 + ((idx * 13791) % 899999)).padStart(6, '0');
			qcRequests.set(secret, {
				secret,
				code,
				authenticated: false,
				expired: false,
				isAdministrator: true,
				userName: 'alice'
			});
			sendJson(res, 200, {
				Secret: secret,
				Code: code,
				Authenticated: false,
				DateAdded: new Date().toISOString()
			});
			return;
		}

		if (method === 'GET' && pathname === '/QuickConnect/Connect') {
			const secret = url.searchParams.get('secret') ?? '';
			const entry = qcRequests.get(secret);
			if (!entry || entry.expired) {
				sendJson(res, 404, { error: 'Unknown Quick Connect secret' });
				return;
			}
			sendJson(res, 200, {
				Secret: entry.secret,
				Code: entry.code,
				Authenticated: entry.authenticated
			});
			return;
		}

		if (method === 'POST' && pathname === '/Users/AuthenticateWithQuickConnect') {
			const raw = preReadBody ?? (await readBody(req));
			let secret = '';
			try {
				const parsed = JSON.parse(raw) as { Secret?: string };
				secret = parsed.Secret ?? '';
			} catch {
				secret = '';
			}
			const entry = qcRequests.get(secret);
			if (!entry || !entry.authenticated || entry.expired) {
				sendJson(res, 401, { error: 'Quick Connect not authenticated' });
				return;
			}
			const tempToken = `e2e-temp-qc-token-${nextKeyCounter++}`;
			tempSessions.set(tempToken, { isAdministrator: entry.isAdministrator, userName: entry.userName });
			sendJson(res, 200, {
				AccessToken: tempToken,
				ServerId: machineId,
				User: {
					Id: entry.isAdministrator ? 'jf-u-alice' : 'jf-u-diana',
					Name: entry.userName,
					Policy: { IsAdministrator: entry.isAdministrator }
				}
			});
			return;
		}

		if (method === 'POST' && pathname === '/Sessions/Logout') {
			const tok = extractToken(req);
			if (tok && tempSessions.has(tok)) {
				tempSessions.delete(tok);
				logoutCount += 1;
				res.writeHead(204);
				res.end();
				return;
			}
			sendJson(res, 401, { error: 'Unauthorized' });
			return;
		}

		if (pathname === '/Auth/Keys') {
			const tok = extractToken(req);
			const temp = tok ? tempSessions.get(tok) : undefined;
			const authorized = (tok !== null && validApiKeys.has(tok)) || Boolean(temp?.isAdministrator);
			if (!authorized) {
				sendJson(res, 401, { error: 'Unauthorized' });
				return;
			}
			if (method === 'POST') {
				const appName = url.searchParams.get('app') ?? 'Miru';
				const newKey = `e2e-jf-qc-key-${nextKeyCounter++}`;
				validApiKeys.add(newKey);
				issuedApiKeys.push(newKey);
				authKeyRecords.push({
					Id: 0,
					AccessToken: newKey,
					AppName: appName,
					IsActive: false,
					DateCreated: jellyfinApiKeyDate(Date.now())
				});
				res.writeHead(204);
				res.end();
				return;
			}
			if (method === 'GET') {
				sendJson(res, 200, { Items: authKeyRecords, TotalRecordCount: authKeyRecords.length });
				return;
			}
		}

		const deleteKeyMatch = pathname.match(/^\/Auth\/Keys\/([^/]+)$/);
		if (method === 'DELETE' && deleteKeyMatch) {
			if (!isAuthorized(req)) {
				sendJson(res, 401, { error: 'Unauthorized' });
				return;
			}
			const keyToDelete = decodeURIComponent(deleteKeyMatch[1]);
			validApiKeys.delete(keyToDelete);
			revokedApiKeys.push(keyToDelete);
			const idx = authKeyRecords.findIndex((k) => k.AccessToken === keyToDelete);
			if (idx >= 0) authKeyRecords.splice(idx, 1);
			res.writeHead(204);
			res.end();
			return;
		}

		if (!isAuthorized(req)) {
			sendJson(res, 401, { error: 'Unauthorized' });
			return;
		}

		if (pathname === '/System/Info') {
			sendJson(res, 200, {
				Id: machineId,
				ServerName: serverName,
				Version: '10.10.3',
				OperatingSystem: 'Linux'
			});
			return;
		}

		if (pathname === '/Sessions') {
			sendJson(res, 200, activeSessions.map(toJellyfinSessionDto));
			return;
		}

		const msgMatch = pathname.match(/^\/Sessions\/([^/]+)\/Message$/);
		if (method === 'POST' && msgMatch) {
			const sid = decodeURIComponent(msgMatch[1]);
			const raw = preReadBody ?? (await readBody(req));
			let text = '';
			try {
				const parsed = JSON.parse(raw) as { Text?: string };
				text = parsed.Text ?? '';
			} catch {
				text = raw;
			}
			pendingMessages.set(sid, text);
			res.writeHead(204);
			res.end();
			return;
		}

		const stopMatch = pathname.match(/^\/Sessions\/([^/]+)\/Playing\/Stop$/);
		if (method === 'POST' && stopMatch) {
			const sid = decodeURIComponent(stopMatch[1]);
			const message = pendingMessages.get(sid) ?? '';
			pendingMessages.delete(sid);
			terminatedSessions.push({ sessionId: sid, message });
			res.writeHead(204);
			res.end();

			setTimeout(() => {
				activeSessions = activeSessions.filter((s) => s.sessionId !== sid);
				broadcastSessions();
			}, TERMINATION_DELAY_MS);
			return;
		}

		if (pathname === '/Users') {
			sendJson(res, 200, [
				{
					Id: 'jf-u-alice',
					Name: 'alice',
					PrimaryImageTag: 'av-alice',
					Policy: { IsAdministrator: true, EnableAllFolders: true, EnabledFolders: [] }
				},
				{
					Id: 'jf-u-diana',
					Name: 'diana',
					PrimaryImageTag: 'av-diana',
					Policy: { IsAdministrator: false, EnableAllFolders: false, EnabledFolders: ['jf-lib-movies'] }
				}
			]);
			return;
		}

		if (pathname === '/Library/VirtualFolders') {
			sendJson(res, 200, [
				{ ItemId: 'jf-lib-movies', Name: 'Jellyfin Movies', CollectionType: 'movies' },
				{ ItemId: 'jf-lib-shows', Name: 'Jellyfin Series', CollectionType: 'tvshows' }
			]);
			return;
		}

		if (pathname === '/Items') {
			const parentId = url.searchParams.get('ParentId');
			const startIndex = Number(url.searchParams.get('StartIndex') ?? '0');
			const limitParam = url.searchParams.get('Limit');
			const limit = limitParam !== null ? Number(limitParam) : 100;
			const includeTypes = url.searchParams.get('IncludeItemTypes');

			let items: MockJfItem[] = [];
			if (parentId === 'jf-lib-movies') {
				items = catalog.movies;
			} else if (parentId === 'jf-lib-shows') {
				if (includeTypes === 'Series') items = catalog.shows;
				else if (includeTypes === 'Season') items = catalog.seasons;
				else items = catalog.episodes;
			}

			const sliced = limit === 0 ? [] : items.slice(startIndex, startIndex + limit);
			sendJson(res, 200, {
				TotalRecordCount: items.length,
				StartIndex: startIndex,
				Items: sliced
			});
			return;
		}

		const ancestorsMatch = pathname.match(/^\/Items\/([^/]+)\/Ancestors$/);
		if (ancestorsMatch) {
			const item = catalog.byId.get(decodeURIComponent(ancestorsMatch[1]));
			if (!item) {
				sendJson(res, 200, []);
				return;
			}
			sendJson(res, 200, [{ Id: item.libraryId, Name: item.libraryName, Type: 'CollectionFolder' }]);
			return;
		}

		const itemMatch = pathname.match(/^\/Items\/([^/]+)$/);
		if (itemMatch) {
			const item = catalog.byId.get(decodeURIComponent(itemMatch[1]));
			if (!item) {
				sendJson(res, 404, { error: 'Not found' });
				return;
			}
			sendJson(res, 200, item);
			return;
		}

		if (/^\/(?:Items|Users)\/[^/]+\/Images\/[^/]+(?:\/[0-9]+)?$/.test(pathname)) {
			res.writeHead(200, {
				'Content-Type': 'image/png',
				'Content-Length': ONE_BY_ONE_PNG.length
			});
			res.end(ONE_BY_ONE_PNG);
			return;
		}

		sendJson(res, 404, { error: `Unhandled mock Jellyfin path: ${pathname}` });
	};

	const server = http.createServer((req, res) => {
		void handleHttpRequest(req, res);
	});

	const wss = new WebSocketServer({ noServer: true });
	server.on('upgrade', (req, socket, head) => {
		const upgradeHeader = String(req.headers.upgrade ?? '').toLowerCase();
		// Java HttpClient sends `Upgrade: h2c` on cleartext HTTP requests by default.
		if (upgradeHeader !== 'websocket') {
			const contentLength = Number(req.headers['content-length'] ?? '0');
			const readUpgradeBody = async (): Promise<string> => {
				let buf: Buffer = Buffer.from(head);
				if (contentLength > 0 && buf.length < contentLength) {
					buf = await new Promise<Buffer>((resolve) => {
						const chunks: Buffer[] = [buf];
						let received = buf.length;
						const finish = () => {
							socket.off('data', onData);
							resolve(Buffer.concat(chunks).subarray(0, contentLength));
						};
						const onData = (chunk: Buffer) => {
							chunks.push(chunk);
							received += chunk.length;
							if (received >= contentLength) finish();
						};
						socket.on('data', onData);
						socket.once('end', finish);
					});
				}
				return buf.toString('utf8');
			};
			void readUpgradeBody().then((bodyText) => {
				const res = new http.ServerResponse(req);
				res.assignSocket(socket as unknown as import('node:net').Socket);
				res.setHeader('Connection', 'close');
				res.on('finish', () => {
					res.detachSocket(socket as unknown as import('node:net').Socket);
					socket.end();
				});
				return handleHttpRequest(req, res, bodyText);
			});
			return;
		}

		const url = new URL(req.url ?? '/', `http://127.0.0.1:${port}`);
		const wsKey = url.searchParams.get('api_key') ?? '';
		if (url.pathname !== '/socket' || !validApiKeys.has(wsKey)) {
			socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
			socket.destroy();
			return;
		}

		wss.handleUpgrade(req, socket, head, (ws) => {
			wsClients.add(ws);
			ws.on('message', (data) => {
				const text = data.toString('utf8');
				if (text.includes('SessionsStart')) sessionsStartCount += 1;
				if (text.includes('"MessageType":"KeepAlive"')) keepAliveCount += 1;
			});
			ws.on('close', () => {
				wsClients.delete(ws);
			});
			ws.send(JSON.stringify({ MessageType: 'ForceKeepAlive', Data: 30 }));
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
		apiKey,
		serverName,
		machineId,
		setActiveSessions(sessions: MockJellyfinSession[]) {
			activeSessions = [...sessions];
		},
		pushSessionsUpdate() {
			broadcastSessions();
		},
		getTerminatedSessions() {
			return terminatedSessions.map((t) => ({ ...t }));
		},
		clearTerminatedSessions() {
			terminatedSessions.length = 0;
		},
		getSessionsStartCount() {
			return sessionsStartCount;
		},
		getKeepAliveCount() {
			return keepAliveCount;
		},
		approveQuickConnect(opts = {}) {
			for (const entry of qcRequests.values()) {
				if (!entry.expired) {
					entry.authenticated = true;
					entry.isAdministrator = opts.isAdministrator ?? true;
					entry.userName = opts.userName ?? (entry.isAdministrator ? 'alice' : 'diana');
				}
			}
		},
		expireQuickConnect() {
			for (const entry of qcRequests.values()) {
				entry.expired = true;
			}
		},
		setQuickConnectEnabled(enabled: boolean) {
			quickConnectEnabled = enabled;
		},
		getIssuedApiKeys() {
			return [...issuedApiKeys];
		},
		getRevokedApiKeys() {
			return [...revokedApiKeys];
		},
		getLogoutCount() {
			return logoutCount;
		},
		waitForWsClient(timeoutMs = 15_000): Promise<void> {
			if (wsClients.size > 0) return Promise.resolve();
			return new Promise<void>((resolve, reject) => {
				const timer = setTimeout(() => {
					reject(new Error(`Timed out after ${timeoutMs}ms waiting for Jellyfin WebSocket client`));
				}, timeoutMs);
				wsWaiters.push(() => {
					clearTimeout(timer);
					resolve();
				});
			});
		},
		async stop() {
			// Safe to call twice: a test may stop the server to simulate an outage before afterAll does
			if (stopped) return;
			stopped = true;
			for (const client of wsClients) {
				client.terminate();
			}
			wsClients.clear();
			await new Promise<void>((resolve) => wss.close(() => resolve()));
			server.closeAllConnections();
			await new Promise<void>((resolve) => server.close(() => resolve()));
		}
	};
}
