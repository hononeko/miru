import { cleanup, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { authStore } from '$stores/auth.svelte';
import AppShell from './AppShell.svelte';

vi.mock('$app/state', () => ({
	page: {
		url: new URL('http://localhost/')
	}
}));

vi.mock('$app/navigation', () => ({
	goto: vi.fn()
}));

describe('AppShell user avatars', () => {
	afterEach(() => {
		cleanup();
		authStore.principal = null;
	});

	it('renders principal avatarUrl across desktop sidebar, tablet rail, and mobile header', () => {
		authStore.principal = {
			accountId: 'acc-1',
			username: 'sergiu',
			email: 'sergiu@miru.local',
			role: 'ADMIN',
			linkedMediaUserIds: ['u-1'],
			authSource: 'SESSION_COOKIE',
			avatarUrl: '/api/v1/users/u-1/avatar'
		};

		render(AppShell);

		const imgs = screen.getAllByRole('img', { name: 'sergiu' });
		expect(imgs).toHaveLength(3);
		for (const img of imgs) {
			expect(img.getAttribute('src')).toBe('/api/v1/users/u-1/avatar');
		}
	});

	it('renders initial fallback across desktop sidebar, tablet rail, and mobile header when avatarUrl is null', () => {
		authStore.principal = {
			accountId: 'acc-2',
			username: 'alice',
			email: null,
			role: 'USER',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE',
			avatarUrl: null
		};

		render(AppShell);

		expect(screen.queryByRole('img')).toBeNull();
		const fallbacks = screen
			.getAllByLabelText('alice')
			.filter((el) => el.tagName === 'SPAN');
		expect(fallbacks).toHaveLength(3);
		for (const el of fallbacks) {
			expect(el.textContent?.trim()).toBe('A');
		}
	});

	it('hides Users and Admin navigation links for USER role while showing them for ADMIN', () => {
		authStore.principal = {
			accountId: 'acc-user',
			username: 'viewer',
			email: null,
			role: 'USER',
			linkedMediaUserIds: ['u-2'],
			authSource: 'SESSION_COOKIE',
			avatarUrl: null
		};

		render(AppShell);
		expect(screen.queryByRole('link', { name: 'Users' })).toBeNull();
		expect(screen.queryByRole('link', { name: 'Admin' })).toBeNull();
		expect(screen.getAllByRole('link', { name: 'Live' }).length).toBeGreaterThan(0);
		expect(screen.getAllByRole('link', { name: 'History' }).length).toBeGreaterThan(0);
		expect(screen.getAllByRole('link', { name: 'Libraries' }).length).toBeGreaterThan(0);

		cleanup();

		authStore.principal = {
			accountId: 'acc-admin',
			username: 'sergiu',
			email: 'sergiu@miru.local',
			role: 'ADMIN',
			linkedMediaUserIds: ['u-1'],
			authSource: 'SESSION_COOKIE',
			avatarUrl: null
		};

		render(AppShell);
		expect(screen.getAllByRole('link', { name: 'Users' }).length).toBeGreaterThan(0);
		expect(screen.getAllByRole('link', { name: 'Admin' }).length).toBeGreaterThan(0);
	});
});
