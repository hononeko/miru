import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authStore } from '$stores/auth.svelte';
import LoginPage from './+page.svelte';

vi.mock('$app/state', () => ({
	page: {
		url: new URL('http://localhost/login')
	}
}));

vi.mock('$app/navigation', () => ({
	goto: vi.fn()
}));

describe('Login page OIDC click handling', () => {
	beforeEach(() => {
		authStore.oidcEnabled = true;
		authStore.localEnabled = true;
		authStore.oidcDisplayName = 'Mock SSO';
		authStore.oidcLoginUrl = '/api/v1/auth/oidc/login';
	});

	afterEach(() => {
		vi.useRealTimers();
		cleanup();
		authStore.oidcEnabled = false;
		authStore.localEnabled = true;
		authStore.oidcDisplayName = 'SSO';
		authStore.oidcLoginUrl = null;
	});

	it('disables the OIDC link on primary click, blocks repeat clicks, and resets on pageshow', async () => {
		render(LoginPage);

		const ssoLink = screen.getByRole('link', { name: 'Sign in with Mock SSO' });
		const submitBtn = screen.getByRole('button', { name: 'Sign In' }) as HTMLButtonElement;
		expect(ssoLink.getAttribute('aria-disabled')).toBeNull();
		expect(submitBtn.disabled).toBe(false);

		// Modifier clicks (e.g. open in new tab) should not lock the current tab button.
		await fireEvent.click(ssoLink, { ctrlKey: true });
		expect(ssoLink.getAttribute('aria-disabled')).toBeNull();
		expect(screen.getByText('Sign in with Mock SSO')).toBeTruthy();

		// Primary click triggers processing state and disables both buttons.
		await fireEvent.click(ssoLink, { button: 0 });
		expect(ssoLink.getAttribute('aria-disabled')).toBe('true');
		expect(ssoLink.getAttribute('aria-busy')).toBe('true');
		expect(ssoLink.getAttribute('tabindex')).toBe('-1');
		expect(screen.getByText('Redirecting to Mock SSO…')).toBeTruthy();
		expect(submitBtn.disabled).toBe(true);

		// Repeat click while redirecting is cancelled.
		const repeatEvent = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 });
		ssoLink.dispatchEvent(repeatEvent);
		expect(repeatEvent.defaultPrevented).toBe(true);

		// Browser bfcache pageshow restores interactive state.
		await fireEvent(window, new Event('pageshow'));
		expect(ssoLink.getAttribute('aria-disabled')).toBeNull();
		expect(ssoLink.getAttribute('aria-busy')).toBeNull();
		expect(screen.getByText('Sign in with Mock SSO')).toBeTruthy();
		expect(submitBtn.disabled).toBe(false);
	});

	it('recovers automatically after the 10s safety fallback timeout if navigation stalls', async () => {
		vi.useFakeTimers();
		render(LoginPage);

		const ssoLink = screen.getByRole('link', { name: 'Sign in with Mock SSO' });
		await fireEvent.click(ssoLink, { button: 0 });
		expect(ssoLink.getAttribute('aria-disabled')).toBe('true');

		await vi.advanceTimersByTimeAsync(10_000);
		expect(ssoLink.getAttribute('aria-disabled')).toBeNull();
		expect(screen.getByText('Sign in with Mock SSO')).toBeTruthy();
	});
});
