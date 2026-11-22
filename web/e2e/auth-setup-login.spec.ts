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

		// 7. Sign out returns to /login
		await page.locator('aside').first().getByRole('button', { name: 'Sign out' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/login`);
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
		await page.locator('aside').first().getByRole('button', { name: 'Sign out' }).click();
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
