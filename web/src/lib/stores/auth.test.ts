import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
import type { AuthProvidersResponse, PrincipalResponse } from '$api/types';
import { AuthStore } from './auth.svelte';

function jsonResponse(status: number): Response {
	return new Response(null, { status });
}

describe('AuthStore provider initialization and logout', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('loads local-only provider mode with setupRequired', async () => {
		const providers: AuthProvidersResponse = {
			authMode: 'local',
			localEnabled: true,
			oidcEnabled: false,
			oidcDisplayName: 'SSO',
			oidcLoginUrl: null,
			oidcLogoutUrl: null,
			setupRequired: true
		};

		const getSpy = vi.spyOn(api, 'GET').mockResolvedValueOnce({
			data: providers,
			response: jsonResponse(200)
		} as never);

		const store = new AuthStore();
		await store.init();

		expect(getSpy).toHaveBeenCalledTimes(1);
		expect(getSpy).toHaveBeenCalledWith('/api/v1/auth/providers');
		expect(store.authMode).toBe('local');
		expect(store.localEnabled).toBe(true);
		expect(store.oidcEnabled).toBe(false);
		expect(store.setupRequired).toBe(true);
		expect(store.authenticated).toBe(false);
		expect(store.loading).toBe(false);
	});

	it('loads OIDC-only mode and authenticates current principal', async () => {
		const providers: AuthProvidersResponse = {
			authMode: 'oidc',
			localEnabled: false,
			oidcEnabled: true,
			oidcDisplayName: 'Authentik',
			oidcLoginUrl: '/api/v1/auth/oidc/login',
			oidcLogoutUrl: '/api/v1/auth/oidc/logout',
			setupRequired: false
		};
		const principal: PrincipalResponse = {
			accountId: 'acc-oidc-1',
			username: 'sergiu',
			email: 'sergiu@miru.local',
			role: 'ADMIN',
			linkedMediaUserIds: ['u-1'],
			authSource: 'SESSION_COOKIE',
			avatarUrl: '/api/v1/users/u-1/avatar'
		};

		vi.spyOn(api, 'GET')
			.mockResolvedValueOnce({ data: providers, response: jsonResponse(200) } as never)
			.mockResolvedValueOnce({ data: principal, response: jsonResponse(200) } as never);

		const store = new AuthStore();
		await store.init();

		expect(store.authMode).toBe('oidc');
		expect(store.localEnabled).toBe(false);
		expect(store.oidcEnabled).toBe(true);
		expect(store.oidcDisplayName).toBe('Authentik');
		expect(store.oidcLoginUrl).toBe('/api/v1/auth/oidc/login');
		expect(store.oidcLogoutUrl).toBe('/api/v1/auth/oidc/logout');
		expect(store.authenticated).toBe(true);
		expect(store.isAdmin).toBe(true);
		expect(store.principal?.avatarUrl).toBe('/api/v1/users/u-1/avatar');
	});

	it('loads hybrid local + OIDC mode and clears session via POST logout when OIDC disabled', async () => {
		const providers: AuthProvidersResponse = {
			authMode: 'local',
			localEnabled: true,
			oidcEnabled: true,
			oidcDisplayName: 'Keycloak',
			oidcLoginUrl: '/api/v1/auth/oidc/login',
			oidcLogoutUrl: '/api/v1/auth/oidc/logout',
			setupRequired: false
		};

		vi.spyOn(api, 'GET')
			.mockResolvedValueOnce({ data: providers, response: jsonResponse(200) } as never)
			.mockResolvedValueOnce({ error: { status: 401 }, response: jsonResponse(401) } as never);

		const store = new AuthStore();
		await store.init();

		expect(store.authMode).toBe('local');
		expect(store.localEnabled).toBe(true);
		expect(store.oidcEnabled).toBe(true);
		expect(store.oidcDisplayName).toBe('Keycloak');
		expect(store.authenticated).toBe(false);

		// Local-only logout uses POST /api/v1/auth/logout
		store.oidcEnabled = false;
		store.oidcLogoutUrl = null;
		store.principal = {
			accountId: 'acc-local',
			username: 'admin',
			role: 'ADMIN',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE'
		};
		const postSpy = vi.spyOn(api, 'POST').mockResolvedValueOnce({ response: jsonResponse(204) } as never);
		await store.logout();
		expect(postSpy).toHaveBeenCalledWith('/api/v1/auth/logout');
		expect(store.principal).toBeNull();
	});

	it('sends a single logout request while one is in progress, and can log out again afterwards', async () => {
		const store = new AuthStore();
		let answer!: (value: unknown) => void;
		const post = vi.spyOn(api, 'POST').mockReturnValueOnce(new Promise((resolve) => (answer = resolve)) as never);

		const first = store.logout();
		expect(store.loggingOut).toBe(true);
		await store.logout();
		answer({ response: jsonResponse(204) });
		await first;
		expect(post).toHaveBeenCalledTimes(1);
		expect(store.loggingOut).toBe(false);

		post.mockRejectedValueOnce(new Error('offline'));
		await expect(store.logout()).rejects.toThrow('offline');
		expect(store.loggingOut).toBe(false);
	});

	it('computes isUnlinkedUser accurately for USER and ADMIN roles', () => {
		const store = new AuthStore();
		expect(store.isUnlinkedUser).toBe(false);

		store.principal = {
			accountId: 'acc-unlinked',
			username: 'bob',
			email: null,
			role: 'USER',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE'
		};
		expect(store.isAdmin).toBe(false);
		expect(store.isUnlinkedUser).toBe(true);

		store.principal = {
			accountId: 'acc-linked',
			username: 'bob',
			email: null,
			role: 'USER',
			linkedMediaUserIds: ['mu-bob'],
			authSource: 'SESSION_COOKIE'
		};
		expect(store.isUnlinkedUser).toBe(false);

		store.principal = {
			accountId: 'acc-admin',
			username: 'admin',
			email: null,
			role: 'ADMIN',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE'
		};
		expect(store.isAdmin).toBe(true);
		expect(store.isUnlinkedUser).toBe(false);
	});
});
