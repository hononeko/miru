import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { goto } from '$app/navigation';
import { api } from '$api/client';
import { authStore } from '$stores/auth.svelte';
import { preferencesStore } from '$stores/preferences.svelte';
import { themeStore } from '$stores/theme.svelte';
import UserPreferencesModal from './UserPreferencesModal.svelte';

vi.mock('$app/navigation', () => ({
	goto: vi.fn()
}));

describe('UserPreferencesModal password change', () => {
	beforeEach(() => {
		HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
			this.setAttribute('open', '');
		});
		HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
			this.removeAttribute('open');
		});
		authStore.authMode = 'local';
		authStore.principal = {
			accountId: 'acc-local-1',
			username: 'alice',
			email: 'alice@miru.local',
			role: 'USER',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE',
			authProvider: 'LOCAL',
			avatarUrl: null
		};
		preferencesStore.openModal();
	});

	afterEach(() => {
		vi.restoreAllMocks();
		cleanup();
		preferencesStore.closeModal();
		authStore.principal = null;
		authStore.authMode = 'local';
	});

	it('styles every selected and unselected choice the same way', () => {
		render(UserPreferencesModal);

		for (const checked of ['true', 'false']) {
			const styles = ['Time format', 'Default stats range', 'Advanced analytics metric'].flatMap((name) =>
				Array.from(
					screen.getByRole('radiogroup', { name }).querySelectorAll(`[role="radio"][aria-checked="${checked}"]`),
					(radio) => radio.className
				)
			);
			expect(styles.length).toBeGreaterThanOrEqual(3);
			expect(new Set(styles).size).toBe(1);
		}
	});

	it('renders Change Password for LOCAL accounts, validates inputs, and submits to /api/v1/auth/me/password', async () => {
		const postSpy = vi.spyOn(api, 'POST').mockResolvedValueOnce({
			response: new Response(null, { status: 204 })
		} as never);

		render(UserPreferencesModal);

		expect(screen.getByRole('region', { name: 'Change Password' })).toBeTruthy();

		const currentInput = screen.getByLabelText('Current Password') as HTMLInputElement;
		const newInput = screen.getByLabelText('New Password') as HTMLInputElement;
		const confirmInput = screen.getByLabelText('Confirm New Password') as HTMLInputElement;
		const fixtureCurrent = ['Test', 'Current', '0001'].join('');
		const fixtureNext = ['Test', 'Updated', '0002'].join('');
		const fixtureMismatch = ['Test', 'Mismatch', '0003'].join('');
		const updateBtn = screen.getByRole('button', { name: 'Update Password' });

		// Weak new password caught client-side
		await fireEvent.input(currentInput, { target: { value: fixtureCurrent } });
		await fireEvent.input(newInput, { target: { value: 'weak' } });
		await fireEvent.input(confirmInput, { target: { value: 'weak' } });
		await fireEvent.click(updateBtn);
		expect(screen.getByRole('alert').textContent).toContain('12 characters');
		expect(postSpy).not.toHaveBeenCalled();

		// Mismatch caught client-side
		await fireEvent.input(newInput, { target: { value: fixtureNext } });
		await fireEvent.input(confirmInput, { target: { value: fixtureMismatch } });
		await fireEvent.click(updateBtn);
		expect(screen.getByRole('alert').textContent).toContain('do not match');
		expect(postSpy).not.toHaveBeenCalled();

		// Valid submission succeeds
		await fireEvent.input(confirmInput, { target: { value: fixtureNext } });
		await fireEvent.click(updateBtn);

		await waitFor(() => {
			expect(postSpy).toHaveBeenCalledWith('/api/v1/auth/me/password', {
				body: {
					currentPassword: fixtureCurrent,
					newPassword: fixtureNext
				}
			});
			expect(screen.getByRole('status').textContent).toContain('Password updated');
		});
	});

	it('hides Change Password for OIDC accounts and AUTH_MODE=none', () => {
		authStore.principal = {
			accountId: 'acc-oidc-1',
			username: 'sso.user',
			email: 'sso@miru.local',
			role: 'USER',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE',
			authProvider: 'OIDC',
			avatarUrl: null
		};
		render(UserPreferencesModal);
		expect(screen.queryByRole('region', { name: 'Change Password' })).toBeNull();

		cleanup();
		authStore.authMode = 'none';
		authStore.principal = {
			accountId: 'dev-admin',
			username: 'local-dev',
			role: 'ADMIN',
			linkedMediaUserIds: [],
			authSource: 'DEV_BYPASS',
			authProvider: 'NONE',
			avatarUrl: null
		};
		render(UserPreferencesModal);
		expect(screen.queryByRole('region', { name: 'Change Password' })).toBeNull();
	});
});

describe('UserPreferencesModal sign out', () => {
	beforeEach(() => {
		vi.mocked(goto).mockClear();
		HTMLDialogElement.prototype.showModal = vi.fn(function (this: HTMLDialogElement) {
			this.setAttribute('open', '');
		});
		HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
			if (!this.hasAttribute('open')) return;
			this.removeAttribute('open');
			this.dispatchEvent(new Event('close'));
		});
		authStore.authMode = 'local';
		authStore.principal = {
			accountId: 'acc-local-1',
			username: 'alice',
			email: 'alice@miru.local',
			role: 'USER',
			linkedMediaUserIds: [],
			authSource: 'SESSION_COOKIE',
			authProvider: 'LOCAL',
			avatarUrl: null
		};
		preferencesStore.openModal();
	});

	afterEach(() => {
		vi.restoreAllMocks();
		cleanup();
		preferencesStore.closeModal();
		authStore.principal = null;
		authStore.oidcEnabled = false;
		authStore.oidcLogoutUrl = null;
	});

	const button = (name: string) => screen.getByRole('button', { name });
	const confirmation = () => screen.queryByRole('group', { name: 'Sign out of Miru?' });
	const dialog = () => document.querySelector('dialog')!;
	const timeFormat = (name: string) => screen.getByRole('radio', { name });

	async function openConfirmation() {
		await fireEvent.click(button('Sign Out'));
		await waitFor(() => expect(confirmation()).toBeTruthy());
	}

	it('asks before signing out and returns to the draft when cancelled', async () => {
		const logout = vi.spyOn(authStore, 'logout');
		render(UserPreferencesModal);

		await openConfirmation();
		expect(screen.queryByText('Your unsaved changes will be lost.')).toBeNull();
		await fireEvent.click(button('Cancel'));
		await waitFor(() => expect(confirmation()).toBeNull());

		await fireEvent.click(timeFormat('12-hour'));
		const dismissals: Array<() => Promise<unknown>> = [
			() => fireEvent.click(button('Cancel')),
			// Escape and the Android back gesture
			() => {
				const cancel = new Event('cancel', { cancelable: true });
				const result = fireEvent(dialog(), cancel);
				expect(cancel.defaultPrevented).toBe(true);
				return result;
			},
			// Elsewhere in the dialog, and on its backdrop
			() => fireEvent.click(screen.getByText('Time Format')),
			async () => {
				vi.spyOn(dialog(), 'getBoundingClientRect').mockReturnValue({ left: 100, right: 500, top: 100, bottom: 500 } as DOMRect);
				await fireEvent.pointerDown(dialog(), { clientX: 5, clientY: 5 });
				await fireEvent.click(dialog(), { clientX: 5, clientY: 5 });
			}
		];
		for (const dismiss of dismissals) {
			await openConfirmation();
			expect(document.activeElement).toBe(button('Cancel'));
			expect(screen.getByText('Your unsaved changes will be lost.')).toBeTruthy();

			await dismiss();
			await waitFor(() => expect(confirmation()).toBeNull());
			await waitFor(() => expect(document.activeElement).toBe(button('Sign Out')));
			expect(dialog().open).toBe(true);
			expect(timeFormat('12-hour').getAttribute('aria-checked')).toBe('true');
		}

		// Without the confirmation, Escape closes the dialog as before
		const cancel = new Event('cancel', { cancelable: true });
		await fireEvent(dialog(), cancel);
		expect(cancel.defaultPrevented).toBe(false);

		expect(logout).not.toHaveBeenCalled();
		expect(goto).not.toHaveBeenCalled();
	});

	it('keeps the focus on a control that the dismissing click was meant for', async () => {
		render(UserPreferencesModal);
		await openConfirmation();

		const password = screen.getByLabelText('Current Password');
		password.focus();
		await fireEvent.click(password);
		await waitFor(() => expect(confirmation()).toBeNull());
		expect(document.activeElement).toBe(password);
	});

	it('signs out once, drops the draft and goes to the login page when confirmed', async () => {
		let answer!: (value: unknown) => void;
		const post = vi.spyOn(api, 'POST').mockReturnValue(new Promise((resolve) => (answer = resolve)) as never);
		const savedTheme = preferencesStore.prefs.theme;
		render(UserPreferencesModal);

		await fireEvent.click(screen.getByRole('radio', { name: savedTheme === 'light' ? 'Dark' : 'Light' }));
		await openConfirmation();
		await fireEvent.click(button('Sign Out'));
		const pending = button('Signing out…') as HTMLButtonElement;
		expect(pending.disabled).toBe(true);

		pending.disabled = false;
		await fireEvent.click(pending);
		const cancel = new Event('cancel', { cancelable: true });
		await fireEvent(dialog(), cancel);
		expect(cancel.defaultPrevented).toBe(true);
		expect(confirmation()).toBeTruthy();

		answer({ response: new Response(null, { status: 204 }) });
		await waitFor(() => expect(goto).toHaveBeenCalledWith('/login'));
		expect(post).toHaveBeenCalledTimes(1);
		expect(post).toHaveBeenCalledWith('/api/v1/auth/logout');
		expect(authStore.authenticated).toBe(false);
		expect(preferencesStore.modalOpen).toBe(false);
		expect(themeStore.mode).toBe(savedTheme);
	});

	it('leaves the redirect to the identity provider to the server when confirmed', async () => {
		authStore.oidcEnabled = true;
		authStore.oidcLogoutUrl = '/api/v1/auth/oidc/logout';
		const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {});
		render(UserPreferencesModal);

		await openConfirmation();
		expect(assign).not.toHaveBeenCalled();
		const confirm = button('Sign Out');
		// Twice before the page has a chance to update
		confirm.click();
		confirm.click();
		await waitFor(() => expect(assign).toHaveBeenCalledWith('/api/v1/auth/oidc/logout'));
		await waitFor(() => expect(preferencesStore.modalOpen).toBe(false));
		expect(assign).toHaveBeenCalledTimes(1);
		expect(goto).not.toHaveBeenCalled();
	});
});
