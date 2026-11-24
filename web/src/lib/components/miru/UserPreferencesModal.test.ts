import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '$api/client';
import { authStore } from '$stores/auth.svelte';
import { preferencesStore } from '$stores/preferences.svelte';
import UserPreferencesModal from './UserPreferencesModal.svelte';

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
