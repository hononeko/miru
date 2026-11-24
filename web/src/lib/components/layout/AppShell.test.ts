import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { goto } from '$app/navigation';
import { api } from '$api/client';
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
		vi.restoreAllMocks();
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

	it('displays server version from /healthz in the sidebar footer', async () => {
		vi.spyOn(api, 'GET').mockResolvedValueOnce({
			data: { status: 'ok', service: 'miru', version: '1.0.0-rc1' },
			response: new Response(null, { status: 200 })
		} as never);

		authStore.principal = {
			accountId: 'acc-1',
			username: 'sergiu',
			role: 'ADMIN',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE',
			avatarUrl: null
		};

		render(AppShell);
		await waitFor(() => {
			expect(screen.getByText('Miru v1.0.0-rc1')).toBeTruthy();
		});
	});
});

describe('AppShell sign out', () => {
	beforeEach(() => {
		vi.mocked(goto).mockClear();
		authStore.authMode = 'local';
		authStore.principal = {
			accountId: 'acc-1',
			username: 'alice',
			email: null,
			role: 'USER',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE',
			avatarUrl: null
		};
	});

	afterEach(() => {
		vi.restoreAllMocks();
		vi.unstubAllGlobals();
		cleanup();
		authStore.principal = null;
		authStore.oidcEnabled = false;
		authStore.oidcLogoutUrl = null;
	});

	const trigger = () => screen.getByRole('button', { name: 'Sign out' });
	const confirmation = () => screen.queryByRole('dialog', { name: 'Sign out of Miru?' });
	const inConfirmation = (name: string) =>
		Array.from(confirmation()!.querySelectorAll('button')).find((b) => b.textContent?.trim() === name)!;

	async function openConfirmation() {
		await fireEvent.click(trigger());
		await waitFor(() => expect(confirmation()).toBeTruthy());
	}

	it('asks before signing out and keeps the session when cancelled', async () => {
		const logout = vi.spyOn(authStore, 'logout');
		render(AppShell);

		await openConfirmation();
		expect(trigger().getAttribute('aria-expanded')).toBe('true');
		await waitFor(() => expect(document.activeElement).toBe(inConfirmation('Cancel')));
		await fireEvent.click(inConfirmation('Cancel'));
		await waitFor(() => expect(confirmation()).toBeNull());

		await openConfirmation();
		await fireEvent.keyDown(document.activeElement!, { key: 'Escape' });
		await waitFor(() => expect(confirmation()).toBeNull());

		expect(logout).not.toHaveBeenCalled();
		expect(authStore.authenticated).toBe(true);
		expect(goto).not.toHaveBeenCalled();
	});

	it('signs out once and goes to the login page when confirmed', async () => {
		let answer!: (value: unknown) => void;
		const post = vi.spyOn(api, 'POST').mockReturnValue(new Promise((resolve) => (answer = resolve)) as never);
		render(AppShell);

		await openConfirmation();
		await fireEvent.click(inConfirmation('Sign Out'));
		const pending = await waitFor(() => {
			const button = inConfirmation('Signing out…');
			expect(button.disabled).toBe(true);
			return button;
		});

		// Neither another press nor dismissing it does anything while the request is in flight
		pending.disabled = false;
		await fireEvent.click(pending);
		await fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' });
		await new Promise((resolve) => setTimeout(resolve, 50));
		expect(trigger().getAttribute('aria-expanded')).toBe('true');
		expect(confirmation()).toBeTruthy();
		expect(goto).not.toHaveBeenCalled();

		answer({ response: new Response(null, { status: 204 }) });
		await waitFor(() => expect(goto).toHaveBeenCalledWith('/login'));
		expect(post).toHaveBeenCalledTimes(1);
		expect(post).toHaveBeenCalledWith('/api/v1/auth/logout');
		expect(authStore.authenticated).toBe(false);
		expect(confirmation()).toBeNull();
	});

	it('leaves the redirect to the identity provider to the server when confirmed', async () => {
		authStore.oidcEnabled = true;
		authStore.oidcLogoutUrl = '/api/v1/auth/oidc/logout';
		const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {});
		const post = vi.spyOn(api, 'POST');
		render(AppShell);

		await openConfirmation();
		expect(assign).not.toHaveBeenCalled();
		const confirm = inConfirmation('Sign Out');
		// Twice before the page has a chance to update
		confirm.click();
		confirm.click();
		await waitFor(() => expect(assign).toHaveBeenCalledWith('/api/v1/auth/oidc/logout'));
		expect(assign).toHaveBeenCalledTimes(1);
		expect(post).not.toHaveBeenCalled();
		expect(goto).not.toHaveBeenCalled();
	});
});
