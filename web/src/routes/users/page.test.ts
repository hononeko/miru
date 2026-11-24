import { cleanup, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
import type { UserSummaryDto } from '$api/types';
import { authStore } from '$stores/auth.svelte';
import UsersPage from './+page.svelte';

vi.mock('$app/navigation', () => ({
	goto: vi.fn()
}));

function makeUser(username: string, isAdmin: boolean, activeStreamCount: number): UserSummaryDto {
	return {
		id: username,
		serverId: 'srv-1',
		externalUserId: username,
		username,
		isAdmin,
		isStreaming: activeStreamCount > 0,
		activeStreamCount,
		lastSeenAtMs: 1_700_000_000_000,
		totalPlays: 1,
		totalWatchDurationSec: 60
	};
}

describe('Users page', () => {
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
	});

	afterEach(() => {
		vi.restoreAllMocks();
		cleanup();
		authStore.principal = null;
	});

	it('shows the role of every user, with a separate LIVE badge while they stream', async () => {
		vi.spyOn(api, 'GET').mockResolvedValue({
			data: {
				users: [
					makeUser('live-admin', true, 2),
					makeUser('live-user', false, 1),
					makeUser('idle-admin', true, 0),
					makeUser('idle-user', false, 0)
				]
			},
			response: new Response(null, { status: 200 })
		} as never);

		render(UsersPage);
		await screen.findByRole('heading', { name: 'live-admin' });

		const badges = (username: string) => {
			const card = screen.getByRole('heading', { name: username }).closest('a')!;
			return Array.from(card.querySelectorAll('span.rounded-full.uppercase'), (b) => b.textContent?.trim());
		};
		expect(badges('live-admin')).toEqual(['ADMIN', 'LIVE · 2']);
		expect(badges('live-user')).toEqual(['USER', 'LIVE · 1']);
		expect(badges('idle-admin')).toEqual(['ADMIN']);
		expect(badges('idle-user')).toEqual(['USER']);
	});
});
