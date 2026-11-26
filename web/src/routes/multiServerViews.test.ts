import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
import type { AdminServerStatusDto, UserSummaryDto } from '$api/types';
import { authStore } from '$stores/auth.svelte';
import { serversStore } from '$stores/servers.svelte';
import AdminPage from './admin/+page.svelte';
import UsersPage from './users/+page.svelte';

vi.mock('$app/navigation', () => ({
	goto: vi.fn()
}));

const ADMIN_SERVERS: AdminServerStatusDto[] = [
	{
		id: 'srv-plex',
		name: 'Home Plex',
		serverType: 'plex',
		configured: true,
		credentialSource: 'ENV_OVERRIDE',
		machineId: 'plex-mid',
		baseUrl: 'http://plex.local:32400',
		reachable: true,
		websocketEnabled: true,
		websocketState: 'CONNECTED',
		canDisconnect: false,
		canRemove: false
	},
	{
		id: 'srv-jf',
		name: 'Studio Jellyfin',
		serverType: 'jellyfin',
		configured: true,
		credentialSource: 'ENV_OVERRIDE',
		machineId: 'jf-mid',
		baseUrl: 'http://jellyfin.local:8096',
		reachable: true,
		websocketEnabled: true,
		websocketState: 'RECONNECTING',
		canDisconnect: false,
		canRemove: false
	}
];

const MULTI_USERS: UserSummaryDto[] = [
	{
		id: 'u-plex-alice',
		serverId: 'srv-plex',
		serverName: 'Home Plex',
		serverType: 'plex',
		externalUserId: '10',
		username: 'alice',
		displayName: 'Alice',
		isAdmin: false,
		isStreaming: false,
		activeStreamCount: 0,
		lastSeenAtMs: 1_700_000_000_000,
		totalPlays: 4,
		totalWatchDurationSec: 240
	},
	{
		id: 'u-jf-alice',
		serverId: 'srv-jf',
		serverName: 'Studio Jellyfin',
		serverType: 'jellyfin',
		externalUserId: 'jf-10',
		username: 'alice',
		displayName: 'Alice',
		isAdmin: true,
		isStreaming: false,
		activeStreamCount: 0,
		lastSeenAtMs: 1_700_000_000_000,
		totalPlays: 2,
		totalWatchDurationSec: 120
	}
];

function mockAdminApi(linkedMediaUserIds: string[], users: UserSummaryDto[] = MULTI_USERS) {
	vi.spyOn(api, 'GET').mockImplementation((async (url: string) => {
		if (url === '/api/v1/admin/accounts') {
			return {
				data: {
					accounts: [
						{
							id: 'acc-2',
							username: 'alice-viewer',
							email: null,
							role: 'USER',
							roleMode: 'MANUAL',
							disabled: false,
							authProvider: 'LOCAL',
							linkedMediaUserIds,
							mediaUserLinkMode: 'MANUAL',
							emailVerified: null,
							createdAtMs: 1_700_000_000_000,
							updatedAtMs: 1_700_000_000_000
						}
					],
					emailLinkingEnabled: false
				},
				response: new Response(null, { status: 200 })
			};
		}
		if (url === '/api/v1/admin/api-keys') return { data: { apiKeys: [] }, response: new Response(null, { status: 200 }) };
		if (url === '/api/v1/users') return { data: { users }, response: new Response(null, { status: 200 }) };
		if (url === '/healthz') {
			return { data: { status: 'ok', service: 'miru', version: '1.2.3-test' }, response: new Response(null, { status: 200 }) };
		}
		if (url === '/readyz') {
			return { data: { status: 'ready', checks: {} }, response: new Response(null, { status: 200 }) };
		}
		if (url === '/api/v1/admin/servers') return { data: { servers: ADMIN_SERVERS }, response: new Response(null, { status: 200 }) };
		if (url === '/api/v1/admin/plex/status') {
			return {
				data: { configured: true, credentialSource: 'ENV_OVERRIDE', encryptionKeyConfigured: true, reachable: true },
				response: new Response(null, { status: 200 })
			};
		}
		if (url === '/api/v1/admin/jellyfin/status') {
			return {
				data: { configured: true, credentialSource: 'ENV_OVERRIDE', encryptionKeyConfigured: true, reachable: true },
				response: new Response(null, { status: 200 })
			};
		}
		if (url === '/api/v1/admin/guardrails') return { data: { rules: [] }, response: new Response(null, { status: 200 }) };
		if (url === '/api/v1/admin/guardrails/audit') {
			return { data: { items: [], totalCount: 0, limit: 20, offset: 0 }, response: new Response(null, { status: 200 }) };
		}
		if (url === '/api/v1/admin/webhooks') {
			return { data: { targets: [], encryptionKeyConfigured: true }, response: new Response(null, { status: 200 }) };
		}
		if (url === '/api/v1/admin/webhooks/deliveries') {
			return { data: { items: [], totalCount: 0, limit: 20, offset: 0 }, response: new Response(null, { status: 200 }) };
		}
		return { data: {}, response: new Response(null, { status: 200 }) };
	}) as never);
}

describe('Multi-server Admin & Users views', () => {
	beforeEach(() => {
		authStore.loading = false;
		authStore.principal = {
			accountId: 'acc-1',
			username: 'admin',
			email: null,
			role: 'ADMIN',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE',
			authProvider: 'LOCAL',
			avatarUrl: null
		};
		serversStore.servers = ADMIN_SERVERS.map((s) => ({
			id: s.id!,
			name: s.name,
			serverType: s.serverType,
			configured: s.configured,
			reachable: s.reachable,
			websocketEnabled: s.websocketEnabled,
			websocketState: s.websocketState
		}));
		serversStore.loaded = true;
	});

	afterEach(() => {
		vi.restoreAllMocks();
		cleanup();
		serversStore.reset();
		authStore.principal = null;
	});

	it('renders per-server readiness cards and unambiguous server badges in RBAC Link Media Users sheet', async () => {
		mockAdminApi(['u-jf-alice']);

		render(AdminPage);

		await waitFor(() => {
			expect(screen.getByText('Home Plex')).toBeTruthy();
			expect(screen.getByText('Studio Jellyfin')).toBeTruthy();
			expect(screen.getByText('WS CONNECTED')).toBeTruthy();
			expect(screen.getByText('WS RECONNECTING')).toBeTruthy();
		});

		expect(screen.getByText('Alice (Studio Jellyfin)')).toBeTruthy();

		await fireEvent.click(screen.getByRole('button', { name: 'Link Users' }));
		await waitFor(() => {
			expect(screen.getByRole('heading', { name: 'Link Media Users · alice-viewer' })).toBeTruthy();
		});
		expect(screen.getByText('PLEX · Home Plex')).toBeTruthy();
		expect(screen.getByText('JELLYFIN · Studio Jellyfin')).toBeTruthy();
	});

	it('links an account to one user per server, replacing the earlier pick and flagging links made before the rule', async () => {
		const bob: UserSummaryDto = { ...MULTI_USERS[0], id: 'u-plex-bob', externalUserId: '11', username: 'bob', displayName: 'Bob' };
		mockAdminApi(['u-plex-alice', 'u-plex-bob'], [...MULTI_USERS, bob]);
		const put = vi.spyOn(api, 'PUT').mockResolvedValue({ data: {}, response: new Response(null, { status: 200 }) } as never);

		render(AdminPage);
		await waitFor(() => expect(screen.getByRole('button', { name: 'Link Users' })).toBeTruthy());
		await fireEvent.click(screen.getByRole('button', { name: 'Link Users' }));
		await waitFor(() => expect(screen.getByRole('heading', { name: 'Link Media Users · alice-viewer' })).toBeTruthy());

		const box = (externalId: string) =>
			screen
				.getAllByRole('checkbox')
				.find((c) => c.closest('label')?.textContent?.includes(`(#${externalId})`)) as HTMLInputElement;
		const save = () => screen.getByRole('button', { name: 'Save Linked Users' }) as HTMLButtonElement;

		// Linked to two Plex users from before the rule: flagged, and saving is blocked until one is dropped
		expect(screen.getByRole('alert').textContent).toContain('Alice and Bob');
		expect(save().disabled).toBe(true);
		await fireEvent.click(box('11'));
		await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
		expect(save().disabled).toBe(false);

		// Picking another Plex user replaces Alice; a user on Jellyfin is added alongside
		await fireEvent.click(box('11'));
		expect(box('10').checked).toBe(false);
		expect(box('11').checked).toBe(true);
		await fireEvent.click(box('jf-10'));
		expect(box('11').checked).toBe(true);
		expect(box('jf-10').checked).toBe(true);

		await fireEvent.click(save());
		await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
		expect(put).toHaveBeenCalledWith('/api/v1/admin/accounts/{id}/media-users', {
			params: { path: { id: 'acc-2' } },
			body: { linkedMediaUserIds: ['u-plex-bob', 'u-jf-alice'] }
		});
	});

	it('filters Users directory by server via ServerFilterBar', async () => {
		const getSpy = vi.spyOn(api, 'GET').mockImplementation((async (_url: string, opts?: { params?: { query?: { serverId?: string } } }) => {
			const sid = opts?.params?.query?.serverId;
			const filtered = sid ? MULTI_USERS.filter((u) => u.serverId === sid) : MULTI_USERS;
			return { data: { users: filtered }, response: new Response(null, { status: 200 }) };
		}) as never);

		render(UsersPage);
		await waitFor(() => {
			expect(screen.getByText('PLEX · Home Plex')).toBeTruthy();
			expect(screen.getByText('JELLYFIN · Studio Jellyfin')).toBeTruthy();
		});

		await fireEvent.click(screen.getByRole('button', { name: /Studio Jellyfin/ }));
		await waitFor(() => {
			expect(getSpy).toHaveBeenCalledWith('/api/v1/users', { params: { query: { serverId: 'srv-jf' } } });
			expect(screen.queryByText('PLEX · Home Plex')).toBeNull();
			expect(screen.getByText('JELLYFIN · Studio Jellyfin')).toBeTruthy();
		});
	});
});
