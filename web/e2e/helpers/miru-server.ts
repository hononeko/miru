import { spawn, type ChildProcess } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Page } from '@playwright/test';
import type { MockOidcServer } from './mock-oidc';
import type { MockPlexServer } from './mock-plex';

const currentDir = path.dirname(fileURLToPath(import.meta.url));
const webDir = path.resolve(currentDir, '../..');
const repoRoot = path.resolve(webDir, '..');
const defaultJarPath = path.join(repoRoot, 'build', 'libs', 'miru-all.jar');
const defaultStaticWebDir = path.join(webDir, 'build');

export const E2E_ADMIN_USERNAME = 'admin';
export const E2E_ADMIN_PASSWORD = 'MiruAdmin1234!';

const SESSION_SECRET = 'miru-e2e-session-secret-0123456789abcdef';
const ENCRYPTION_KEY = 'miru-e2e-encryption-key-0123456789abcdef';

export interface MiruServerOptions {
	mockPlex?: MockPlexServer;
	connectStaticPlex?: boolean;
	mockOidc?: MockOidcServer;
	oidcDisplayName?: string;
	bootstrapAdmin?: boolean;
	adminUsername?: string;
	adminPassword?: string;
	extraEnv?: Record<string, string>;
}

export interface ApiUserSummary {
	id: string;
	username: string;
	displayName?: string | null;
	externalUserId: string;
	isAdmin: boolean;
	totalPlays: number;
}

export interface MiruServer {
	readonly port: number;
	readonly baseUrl: string;
	readonly tmpDir: string;
	readonly logs: string[];
	apiLogin(username: string, password: string): Promise<string>;
	apiCreateAccount(
		cookie: string,
		body: { username: string; password: string; email?: string | null; role: 'ADMIN' | 'USER'; linkedMediaUserIds?: string[] }
	): Promise<{ id: string; username: string; role: string }>;
	apiImportTautulli(cookie: string, dbFilePath: string, dryRun?: boolean): Promise<Record<string, unknown>>;
	apiGetUsers(cookie: string): Promise<ApiUserSummary[]>;
	apiGetLibraries(cookie: string): Promise<Array<{ id: string; sectionKey: string; title: string }>>;
	apiTriggerLibrarySync(cookie: string, deep?: boolean): Promise<Record<string, unknown>>;
	waitForUsers(cookie: string, minCount: number, timeoutMs?: number): Promise<ApiUserSummary[]>;
	waitForLibraries(cookie: string, minCount: number, timeoutMs?: number): Promise<Array<{ id: string; sectionKey: string; title: string }>>;
	stop(): Promise<void>;
}

async function allocateFreePort(): Promise<number> {
	return new Promise((resolve, reject) => {
		const srv = net.createServer();
		srv.unref();
		srv.on('error', reject);
		srv.listen(0, '127.0.0.1', () => {
			const addr = srv.address();
			if (!addr || typeof addr === 'string') {
				srv.close(() => reject(new Error('Failed to allocate port')));
				return;
			}
			const { port } = addr;
			srv.close(() => resolve(port));
		});
	});
}

async function waitForHealthz(baseUrl: string, proc: ChildProcess, logs: string[], timeoutMs = 25_000): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (proc.exitCode !== null) {
			throw new Error(`Miru exited prematurely with code ${proc.exitCode}.\nLogs:\n${logs.join('')}`);
		}
		try {
			const res = await fetch(`${baseUrl}/healthz`);
			if (res.ok) return;
		} catch {
			// Server still starting up
		}
		await new Promise((r) => setTimeout(r, 150));
	}
	throw new Error(`Timed out waiting for Miru /healthz at ${baseUrl}.\nLogs:\n${logs.join('')}`);
}

export async function startMiruServer(options: MiruServerOptions = {}): Promise<MiruServer> {
	if (!fs.existsSync(defaultJarPath)) {
		throw new Error(
			`Missing backend shadow JAR at ${defaultJarPath}. Run "./gradlew shadowJar" before E2E tests.`
		);
	}
	if (!fs.existsSync(path.join(defaultStaticWebDir, 'index.html'))) {
		throw new Error(
			`Missing built frontend bundle at ${defaultStaticWebDir}. Run "bun run build" in web/ before E2E tests.`
		);
	}

	const port = await allocateFreePort();
	const baseUrl = `http://127.0.0.1:${port}`;
	const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'miru-e2e-server-'));
	const sqlitePath = path.join(tmpDir, 'miru.db');

	const env: NodeJS.ProcessEnv = {
		...process.env,
		MIRU_HOST: '127.0.0.1',
		MIRU_PORT: String(port),
		DB_ENGINE: 'sqlite',
		SQLITE_PATH: sqlitePath,
		STATIC_WEB_DIR: defaultStaticWebDir,
		AUTH_MODE: 'local',
		AUTH_ALLOW_LOCAL_LOGIN: 'true',
		AUTH_SESSION_SECRET: SESSION_SECRET,
		MIRU_ENCRYPTION_KEY: ENCRYPTION_KEY,
		MIN_PLAY_DURATION_SECONDS: '0',
		RESUME_MERGE_WINDOW_SECONDS: '0',
		PLEX_POLL_INTERVAL_SECONDS: '5',
		PLEX_WEBSOCKET_ENABLED: 'true'
	};

	if (options.mockPlex) {
		env.PLEX_TV_BASE_URL = options.mockPlex.baseUrl;
		if (options.connectStaticPlex !== false) {
			env.PLEX_URL = options.mockPlex.baseUrl;
			env.PLEX_TOKEN = options.mockPlex.token;
		}
	}

	if (options.bootstrapAdmin !== false) {
		env.MIRU_ADMIN_USERNAME = options.adminUsername ?? E2E_ADMIN_USERNAME;
		env.MIRU_ADMIN_PASSWORD = options.adminPassword ?? E2E_ADMIN_PASSWORD;
	}

	if (options.mockOidc) {
		env.OIDC_ENABLED = 'true';
		env.OIDC_ISSUER_URL = options.mockOidc.issuerUrl;
		env.OIDC_CLIENT_ID = options.mockOidc.clientId;
		env.OIDC_CLIENT_SECRET = options.mockOidc.clientSecret;
		env.OIDC_REDIRECT_URI = `${baseUrl}/api/v1/auth/oidc/callback`;
		env.OIDC_POST_LOGOUT_REDIRECT_URI = `${baseUrl}/login`;
		env.OIDC_DISPLAY_NAME = options.oidcDisplayName ?? 'Mock SSO';
		env.OIDC_ADMIN_GROUPS = 'miru-admins';
		env.OIDC_USER_GROUPS = 'miru-users';
		env.OIDC_AUTO_LINK_BY_EMAIL = 'true';
	}

	if (options.extraEnv) {
		Object.assign(env, options.extraEnv);
	}

	const logs: string[] = [];
	const proc = spawn('java', ['-Xmx256m', '-jar', defaultJarPath], {
		env,
		stdio: ['ignore', 'pipe', 'pipe']
	});

	proc.stdout?.on('data', (chunk: Buffer) => logs.push(chunk.toString('utf8')));
	proc.stderr?.on('data', (chunk: Buffer) => logs.push(chunk.toString('utf8')));

	try {
		await waitForHealthz(baseUrl, proc, logs);
	} catch (err) {
		proc.kill('SIGKILL');
		fs.rmSync(tmpDir, { recursive: true, force: true });
		throw err;
	}

	const apiLogin = async (username: string, password: string): Promise<string> => {
		const res = await fetch(`${baseUrl}/api/v1/auth/login`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ username, password })
		});
		if (!res.ok) {
			throw new Error(`API login failed for ${username}: HTTP ${res.status} ${await res.text()}`);
		}
		const setCookie = res.headers.get('set-cookie');
		if (!setCookie) {
			throw new Error(`API login returned no Set-Cookie header for ${username}`);
		}
		return setCookie.split(';')[0];
	};

	const apiCreateAccount = async (
		cookie: string,
		body: { username: string; password: string; email?: string | null; role: 'ADMIN' | 'USER'; linkedMediaUserIds?: string[] }
	) => {
		const res = await fetch(`${baseUrl}/api/v1/admin/accounts`, {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				Cookie: cookie
			},
			body: JSON.stringify(body)
		});
		if (!res.ok) {
			throw new Error(`Create account failed: HTTP ${res.status} ${await res.text()}`);
		}
		return (await res.json()) as { id: string; username: string; role: string };
	};

	const apiImportTautulli = async (cookie: string, dbFilePath: string, dryRun = false) => {
		const fileBytes = fs.readFileSync(dbFilePath);
		const form = new FormData();
		form.append('file', new Blob([fileBytes], { type: 'application/x-sqlite3' }), 'tautulli.db');
		const res = await fetch(`${baseUrl}/api/v1/admin/imports/tautulli?dryRun=${dryRun}`, {
			method: 'POST',
			headers: { Cookie: cookie },
			body: form
		});
		if (!res.ok) {
			throw new Error(`Tautulli import failed: HTTP ${res.status} ${await res.text()}`);
		}
		return (await res.json()) as Record<string, unknown>;
	};

	const apiGetUsers = async (cookie: string): Promise<ApiUserSummary[]> => {
		const res = await fetch(`${baseUrl}/api/v1/users`, {
			headers: { Cookie: cookie }
		});
		if (!res.ok) {
			throw new Error(`Fetch users failed: HTTP ${res.status}`);
		}
		const data = (await res.json()) as { users: ApiUserSummary[] };
		return data.users;
	};

	const apiGetLibraries = async (cookie: string) => {
		const res = await fetch(`${baseUrl}/api/v1/libraries`, {
			headers: { Cookie: cookie }
		});
		if (!res.ok) {
			throw new Error(`Fetch libraries failed: HTTP ${res.status}`);
		}
		const data = (await res.json()) as { libraries: Array<{ id: string; sectionKey: string; title: string }> };
		return data.libraries;
	};

	const apiTriggerLibrarySync = async (cookie: string, deep = true) => {
		const res = await fetch(`${baseUrl}/api/v1/libraries/sync?deep=${deep}`, {
			method: 'POST',
			headers: { Cookie: cookie }
		});
		if (!res.ok) {
			throw new Error(`Library sync failed: HTTP ${res.status} ${await res.text()}`);
		}
		return (await res.json()) as Record<string, unknown>;
	};

	const waitForUsers = async (cookie: string, minCount: number, timeoutMs = 15_000): Promise<ApiUserSummary[]> => {
		const deadline = Date.now() + timeoutMs;
		let latest: ApiUserSummary[] = [];
		while (Date.now() < deadline) {
			latest = await apiGetUsers(cookie);
			if (latest.length >= minCount) return latest;
			await new Promise((r) => setTimeout(r, 200));
		}
		throw new Error(`Timed out waiting for >= ${minCount} users (got ${latest.length})`);
	};

	const waitForLibraries = async (cookie: string, minCount: number, timeoutMs = 15_000) => {
		const deadline = Date.now() + timeoutMs;
		let latest: Array<{ id: string; sectionKey: string; title: string }> = [];
		while (Date.now() < deadline) {
			latest = await apiGetLibraries(cookie);
			if (latest.length >= minCount) return latest;
			await new Promise((r) => setTimeout(r, 200));
		}
		throw new Error(`Timed out waiting for >= ${minCount} library sections (got ${latest.length})`);
	};

	const stop = async () => {
		if (proc.exitCode === null) {
			await new Promise<void>((resolve) => {
				const killTimer = setTimeout(() => {
					if (proc.exitCode === null) proc.kill('SIGKILL');
				}, 5_000);
				proc.once('exit', () => {
					clearTimeout(killTimer);
					resolve();
				});
				proc.kill('SIGTERM');
			});
		}
		fs.rmSync(tmpDir, { recursive: true, force: true });
	};

	return {
		port,
		baseUrl,
		tmpDir,
		logs,
		apiLogin,
		apiCreateAccount,
		apiImportTautulli,
		apiGetUsers,
		apiGetLibraries,
		apiTriggerLibrarySync,
		waitForUsers,
		waitForLibraries,
		stop
	};
}

export async function loginViaUi(page: Page, baseUrl: string, username: string, password: string): Promise<void> {
	await page.goto(`${baseUrl}/login`);
	await page.getByLabel('Username').fill(username);
	await page.getByLabel('Password').fill(password);
	await page.getByRole('button', { name: 'Sign In', exact: true }).click();
	await page.waitForURL(`${baseUrl}/`);
	await page.getByRole('heading', { name: 'Live Activity' }).waitFor({ state: 'visible' });
}
