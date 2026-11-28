import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
import { authStore } from '$stores/auth.svelte';
import HistoryPage from './+page.svelte';

const pageState = vi.hoisted(() => ({ url: new URL('http://localhost/history') }));

vi.mock('$app/state', () => ({
	page: pageState
}));

vi.mock('$app/navigation', () => ({
	goto: vi.fn()
}));

const FROM = '2026-11-20T21:00:00.000Z';
const TO = '2026-11-20T21:59:59.999Z';

describe('History time window from the URL', () => {
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
		pageState.url = new URL('http://localhost/history');
	});

	function spyOnHistory() {
		return vi.spyOn(api, 'GET').mockImplementation((async (url: string) => {
			if (url === '/api/v1/users') {
				return { data: { users: [] }, response: new Response(null, { status: 200 }) };
			}
			return {
				data: { items: [], page: 1, pageSize: 25, totalCount: 0, totalPages: 0 },
				response: new Response(null, { status: 200 })
			};
		}) as never);
	}

	const historyQueries = (spy: ReturnType<typeof spyOnHistory>) =>
		(spy.mock.calls as unknown as Array<[string, { params: { query: Record<string, unknown> } }]>)
			.filter(([url]) => url === '/api/v1/history')
			.map(([, init]) => init.params.query);

	it('filters to the linked hour, names it in a chip, and lets the chip remove it', async () => {
		pageState.url = new URL(`http://localhost/history?${new URLSearchParams({ from: FROM, to: TO, serverId: 'srv-plex' })}`);
		const spy = spyOnHistory();
		render(HistoryPage);

		await waitFor(() =>
			expect(historyQueries(spy).at(-1)).toEqual(
				expect.objectContaining({ from: FROM, to: TO, serverId: 'srv-plex', page: 1 })
			)
		);
		const chip = await screen.findByTestId('history-time-window');
		expect(chip.textContent).toContain('21:00–21:59 UTC');

		await fireEvent.click(screen.getByRole('button', { name: 'Remove the time filter' }));
		await waitFor(() => expect(screen.queryByTestId('history-time-window')).toBeNull());
		await waitFor(() =>
			expect(historyQueries(spy).at(-1)).toEqual(expect.objectContaining({ from: undefined, to: undefined }))
		);
		// The server filter from the same link stays
		expect(historyQueries(spy).at(-1)).toEqual(expect.objectContaining({ serverId: 'srv-plex' }));
	});

	it('ignores a time bound that is not a date and shows a window that is not one hour by its ends', async () => {
		pageState.url = new URL(
			`http://localhost/history?${new URLSearchParams({ from: '2026-11-20T08:15:00.000Z', to: 'not-a-date' })}`
		);
		const spy = spyOnHistory();
		render(HistoryPage);

		await waitFor(() =>
			expect(historyQueries(spy).at(-1)).toEqual(
				expect.objectContaining({ from: '2026-11-20T08:15:00.000Z', to: undefined })
			)
		);
		expect((await screen.findByTestId('history-time-window')).textContent).toMatch(/since .*08:15 UTC/);

		// Reset clears the time window along with the other filters
		await fireEvent.click(screen.getByRole('button', { name: /Reset/ }));
		await waitFor(() => expect(screen.queryByTestId('history-time-window')).toBeNull());
	});
});
