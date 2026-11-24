import { expect, test } from '@playwright/test';
import { startMockOidcServer, type MockOidcServer } from './helpers/mock-oidc';
import { startMockPlexServer, type MockPlexServer } from './helpers/mock-plex';
import { E2E_ADMIN_PASSWORD, E2E_ADMIN_USERNAME, startMiruServer, type MiruServer } from './helpers/miru-server';

test.describe('First-run setup, login, session persistence, sign-out & OIDC SSO', () => {
	let mockPlex: MockPlexServer;
	let mockOidc: MockOidcServer;
	let miru: MiruServer;

	test.beforeAll(async () => {
		mockPlex = await startMockPlexServer();
		mockOidc = await startMockOidcServer();
		miru = await startMiruServer({
			mockPlex,
			mockOidc,
			oidcDisplayName: 'Mock SSO',
			bootstrapAdmin: false
		});
	});

	test.afterAll(async () => {
		await miru?.stop();
		await mockOidc?.stop();
		await mockPlex?.stop();
	});

	test('validates first-run /setup password rules, persists session, signs out, logs in locally and via OIDC', async ({
		page
	}) => {
		// 1. First run redirects automatically to /setup
		await page.goto(miru.baseUrl);
		await expect(page).toHaveURL(`${miru.baseUrl}/setup`);
		await expect(page.getByRole('heading', { name: 'First-Run Setup' })).toBeVisible();
		await expect(page.getByText('SETUP REQUIRED')).toBeVisible();

		// 2. Password complexity rejection (12+ chars but missing uppercase & digit)
		await page.getByLabel('Admin Username').fill(E2E_ADMIN_USERNAME);
		await page.getByLabel('Email (optional)').fill('admin@example.test');
		await page.getByLabel(/^Password\b/).fill('alllowercaseonly');
		await page.getByLabel('Confirm Password').fill('alllowercaseonly');
		await page.getByRole('button', { name: 'Create Admin Account & Sign In' }).click();
		await expect(page.getByRole('alert')).toContainText(/uppercase|digit|character/i);
		await expect(page).toHaveURL(`${miru.baseUrl}/setup`);

		// 3. Password mismatch rejection
		await page.getByLabel(/^Password\b/).fill(E2E_ADMIN_PASSWORD);
		await page.getByLabel('Confirm Password').fill('DifferentPass1234!');
		await page.getByRole('button', { name: 'Create Admin Account & Sign In' }).click();
		await expect(page.getByRole('alert')).toHaveText('Passwords do not match.');

		// 4. Valid initial admin setup signs in and redirects to Live Activity (/)
		await page.getByLabel('Confirm Password').fill(E2E_ADMIN_PASSWORD);
		await page.getByRole('button', { name: 'Create Admin Account & Sign In' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/`);
		await expect(page.getByRole('heading', { name: 'Live Activity' })).toBeVisible();
		await expect(page.locator('aside').first()).toContainText(E2E_ADMIN_USERNAME);

		// 5. Session persists across full page reload
		await page.reload();
		await expect(page).toHaveURL(`${miru.baseUrl}/`);
		await expect(page.getByRole('heading', { name: 'Live Activity' })).toBeVisible();

		// 6. Visiting /setup after setup is complete redirects back to /
		await page.goto(`${miru.baseUrl}/setup`);
		await expect(page).toHaveURL(`${miru.baseUrl}/`);

		// 7. Sign out asks first; Cancel, Escape and pressing elsewhere keep the session
		const signOut = page.locator('aside').first().getByRole('button', { name: 'Sign out' });
		const signOutConfirmation = page.getByRole('dialog', { name: 'Sign out of Miru?' });
		const dismissals = [
			() => signOutConfirmation.getByRole('button', { name: 'Cancel' }).click(),
			() => page.keyboard.press('Escape'),
			() => page.mouse.click(700, 300)
		];
		for (const dismiss of dismissals) {
			await signOut.click();
			await expect(signOutConfirmation.getByRole('button', { name: 'Cancel' })).toBeFocused();
			await dismiss();
			await expect(signOutConfirmation).toBeHidden();
			await expect(signOut).toBeFocused();
			await expect(page).toHaveURL(`${miru.baseUrl}/`);
		}

		// Reachable by keyboard alone; Tab stays inside
		await page.keyboard.press('Enter');
		await expect(signOutConfirmation.getByRole('button', { name: 'Cancel' })).toBeFocused();
		await page.keyboard.press('Tab');
		await expect(signOutConfirmation.getByRole('button', { name: 'Sign Out' })).toBeFocused();
		await page.keyboard.press('Tab');
		await expect(signOutConfirmation.getByRole('button', { name: 'Cancel' })).toBeFocused();

		// Confirming sends one request and returns to /login
		let logoutRequests = 0;
		page.on('request', (request) => {
			if (/\/auth\/(oidc\/)?logout$/.test(new URL(request.url()).pathname)) logoutRequests++;
		});
		// The app shows /login right away; the redirect through the provider then loads it again
		const redirectedBack = page.waitForEvent('load');
		await signOutConfirmation.getByRole('button', { name: 'Sign Out' }).dblclick({ noWaitAfter: true }).catch(() => {});
		await redirectedBack;
		await expect(page).toHaveURL(`${miru.baseUrl}/login`);
		expect(logoutRequests).toBe(1);
		await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();

		// 8. Invalid password is rejected on /login
		await page.getByLabel('Username').fill(E2E_ADMIN_USERNAME);
		await page.getByLabel('Password').fill('WrongPassword999!');
		await page.getByRole('button', { name: 'Sign In', exact: true }).click();
		await expect(page.getByRole('alert')).toBeVisible();
		await expect(page).toHaveURL(`${miru.baseUrl}/login`);

		// 9. Valid local login succeeds
		await page.getByLabel('Password').fill(E2E_ADMIN_PASSWORD);
		await page.getByRole('button', { name: 'Sign In', exact: true }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/`);
		await expect(page.getByRole('heading', { name: 'Live Activity' })).toBeVisible();

		// Sign out before testing OIDC SSO
		await signOut.click();
		const redirectedBackAgain = page.waitForEvent('load');
		await signOutConfirmation.getByRole('button', { name: 'Sign Out' }).click();
		await redirectedBackAgain;
		await expect(page).toHaveURL(`${miru.baseUrl}/login`);

		// 10. OIDC user outside authorized groups is denied and redirected back to /login?error=forbidden
		mockOidc.setNextUser({
			sub: 'oidc-sub-denied',
			preferredUsername: 'outsider',
			name: 'Outsider',
			email: 'outsider@example.test',
			groups: ['unrelated-group']
		});
		await page.getByRole('link', { name: 'Sign in with Mock SSO' }).click();
		await expect(page).toHaveURL(/\/login\?error=forbidden$/);
		await expect(page.getByRole('alert')).toContainText('not a member of an authorized Miru group');

		// 11. Authorized OIDC admin completes PKCE S256 + JWKS login and lands on /
		mockOidc.setNextUser({
			sub: 'oidc-sub-admin',
			preferredUsername: 'ssoadmin',
			name: 'SSO Admin',
			email: 'ssoadmin@example.test',
			groups: ['miru-admins']
		});
		await page.getByRole('link', { name: 'Sign in with Mock SSO' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/`);
		await expect(page.getByRole('heading', { name: 'Live Activity' })).toBeVisible();
		await expect(page.locator('aside').first()).toContainText('ssoadmin');
		await expect(page.locator('aside').first().getByRole('link', { name: 'Admin' })).toBeVisible();
	});
});
