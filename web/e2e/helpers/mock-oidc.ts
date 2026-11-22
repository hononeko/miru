import crypto from 'node:crypto';
import http from 'node:http';
import type { AddressInfo } from 'node:net';

export interface MockOidcPersona {
	sub: string;
	preferredUsername: string;
	name: string;
	email: string;
	groups: string[];
}

export interface MockOidcServer {
	readonly port: number;
	readonly issuerUrl: string;
	readonly clientId: string;
	readonly clientSecret: string;
	setNextUser(persona: MockOidcPersona): void;
	stop(): Promise<void>;
}

interface PendingAuthCode {
	clientId: string;
	redirectUri: string;
	nonce: string;
	codeChallenge: string;
	persona: MockOidcPersona;
}

function readBody(req: http.IncomingMessage): Promise<string> {
	return new Promise((resolve, reject) => {
		const chunks: Buffer[] = [];
		req.on('data', (chunk: Buffer) => chunks.push(chunk));
		req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
		req.on('error', reject);
	});
}

function base64UrlJson(value: unknown): string {
	return Buffer.from(JSON.stringify(value), 'utf8').toString('base64url');
}

export async function startMockOidcServer(options?: {
	clientId?: string;
	clientSecret?: string;
	initialUser?: MockOidcPersona;
}): Promise<MockOidcServer> {
	const clientId = options?.clientId ?? 'miru-e2e-oidc-client';
	const clientSecret = options?.clientSecret ?? 'miru-e2e-oidc-client-secret';
	const kid = 'miru-e2e-rsa-key-1';

	const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', {
		modulusLength: 2048
	});
	const publicJwk = {
		...publicKey.export({ format: 'jwk' }),
		kid,
		use: 'sig',
		alg: 'RS256'
	};

	let currentUser: MockOidcPersona = options?.initialUser ?? {
		sub: 'oidc-sub-admin-1',
		preferredUsername: 'ssoadmin',
		name: 'SSO Admin',
		email: 'ssoadmin@example.test',
		groups: ['miru-admins']
	};

	const authCodes = new Map<string, PendingAuthCode>();
	const accessTokens = new Map<string, MockOidcPersona>();

	let port = 0;
	let issuerUrl = '';

	const signIdToken = (payload: Record<string, unknown>): string => {
		const header = base64UrlJson({ alg: 'RS256', typ: 'JWT', kid });
		const body = base64UrlJson(payload);
		const signingInput = `${header}.${body}`;
		const signature = crypto
			.createSign('RSA-SHA256')
			.update(signingInput)
			.sign(privateKey, 'base64url');
		return `${signingInput}.${signature}`;
	};

	const sendJson = (res: http.ServerResponse, status: number, body: unknown) => {
		const payload = JSON.stringify(body);
		res.writeHead(status, {
			'Content-Type': 'application/json',
			'Content-Length': Buffer.byteLength(payload)
		});
		res.end(payload);
	};

	const server = http.createServer(async (req, res) => {
		const url = new URL(req.url ?? '/', `http://127.0.0.1:${port}`);
		const pathname = url.pathname;
		const method = req.method ?? 'GET';

		if (method === 'GET' && pathname === '/.well-known/openid-configuration') {
			sendJson(res, 200, {
				issuer: issuerUrl,
				authorization_endpoint: `${issuerUrl}/authorize`,
				token_endpoint: `${issuerUrl}/token`,
				userinfo_endpoint: `${issuerUrl}/userinfo`,
				jwks_uri: `${issuerUrl}/jwks.json`,
				end_session_endpoint: `${issuerUrl}/logout`,
				id_token_signing_alg_values_supported: ['RS256']
			});
			return;
		}

		if (method === 'GET' && pathname === '/jwks.json') {
			sendJson(res, 200, { keys: [publicJwk] });
			return;
		}

		if (method === 'GET' && pathname === '/authorize') {
			const reqClientId = url.searchParams.get('client_id') ?? '';
			const redirectUri = url.searchParams.get('redirect_uri') ?? '';
			const state = url.searchParams.get('state') ?? '';
			const nonce = url.searchParams.get('nonce') ?? '';
			const codeChallenge = url.searchParams.get('code_challenge') ?? '';

			if (reqClientId !== clientId || !redirectUri) {
				sendJson(res, 400, { error: 'invalid_request' });
				return;
			}

			const code = crypto.randomBytes(16).toString('hex');
			authCodes.set(code, {
				clientId: reqClientId,
				redirectUri,
				nonce,
				codeChallenge,
				persona: { ...currentUser, groups: [...currentUser.groups] }
			});

			const callbackUrl = new URL(redirectUri);
			callbackUrl.searchParams.set('code', code);
			if (state) callbackUrl.searchParams.set('state', state);

			res.writeHead(302, { Location: callbackUrl.toString() });
			res.end();
			return;
		}

		if (method === 'POST' && pathname === '/token') {
			const raw = await readBody(req);
			const params = new URLSearchParams(raw);
			const code = params.get('code') ?? '';
			const reqClientId = params.get('client_id') ?? '';
			const reqClientSecret = params.get('client_secret') ?? '';
			const codeVerifier = params.get('code_verifier') ?? '';
			const redirectUri = params.get('redirect_uri') ?? '';

			const entry = authCodes.get(code);
			authCodes.delete(code);
			if (!entry || entry.clientId !== reqClientId || entry.redirectUri !== redirectUri) {
				sendJson(res, 400, { error: 'invalid_grant' });
				return;
			}
			if (clientSecret && reqClientSecret !== clientSecret) {
				sendJson(res, 401, { error: 'invalid_client' });
				return;
			}
			const computedChallenge = crypto
				.createHash('sha256')
				.update(codeVerifier, 'ascii')
				.digest('base64url');
			if (entry.codeChallenge && computedChallenge !== entry.codeChallenge) {
				sendJson(res, 400, { error: 'invalid_grant', error_description: 'PKCE verification failed' });
				return;
			}

			const nowSec = Math.floor(Date.now() / 1000);
			const idToken = signIdToken({
				iss: issuerUrl,
				sub: entry.persona.sub,
				aud: clientId,
				nonce: entry.nonce,
				iat: nowSec,
				exp: nowSec + 3600,
				preferred_username: entry.persona.preferredUsername,
				name: entry.persona.name,
				email: entry.persona.email,
				email_verified: true,
				groups: entry.persona.groups
			});

			const accessToken = crypto.randomBytes(20).toString('hex');
			accessTokens.set(accessToken, entry.persona);

			sendJson(res, 200, {
				access_token: accessToken,
				token_type: 'Bearer',
				expires_in: 3600,
				id_token: idToken
			});
			return;
		}

		if (method === 'GET' && pathname === '/userinfo') {
			const authHeader = req.headers.authorization ?? '';
			const bearer = authHeader.replace(/^Bearer\s+/i, '');
			const persona = accessTokens.get(bearer);
			if (!persona) {
				sendJson(res, 401, { error: 'invalid_token' });
				return;
			}
			sendJson(res, 200, {
				sub: persona.sub,
				preferred_username: persona.preferredUsername,
				name: persona.name,
				email: persona.email,
				email_verified: true,
				groups: persona.groups
			});
			return;
		}

		if (method === 'GET' && pathname === '/logout') {
			const postLogout = url.searchParams.get('post_logout_redirect_uri') ?? '/';
			res.writeHead(302, { Location: postLogout });
			res.end();
			return;
		}

		sendJson(res, 404, { error: `Unhandled mock OIDC path: ${pathname}` });
	});

	await new Promise<void>((resolve, reject) => {
		server.once('error', reject);
		server.listen(0, '127.0.0.1', () => {
			const addr = server.address() as AddressInfo;
			port = addr.port;
			issuerUrl = `http://127.0.0.1:${port}`;
			resolve();
		});
	});

	return {
		get port() {
			return port;
		},
		get issuerUrl() {
			return issuerUrl;
		},
		clientId,
		clientSecret,
		setNextUser(persona: MockOidcPersona) {
			currentUser = { ...persona, groups: [...persona.groups] };
		},
		async stop() {
			await new Promise<void>((resolve) => server.close(() => resolve()));
		}
	};
}
