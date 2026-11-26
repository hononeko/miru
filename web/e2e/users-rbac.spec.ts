import { expect, test } from '@playwright/test';
import { startMockPlexServer, type MockPlexServer } from './helpers/mock-plex';
import {
	E2E_ADMIN_PASSWORD,
	E2E_ADMIN_USERNAME,
	loginViaUi,
	startMiruServer,
	type MiruServer
} from './helpers/miru-server';
import { createTautulliFixture, type TautulliFixture } from './helpers/tautulli-fixture';

const USER_PASSWORD = 'ScopedUserPass123!';

test.describe('Users & RBAC isolation (ADMIN vs scoped USER vs unlinked USER)', () => {
	let mockPlex: MockPlexServer;
	let miru: MiruServer;
	let fixture: TautulliFixture;
	let aliceMediaUserId: string;
	let bobMediaUserId: string;

	test.beforeAll(async () => {
		mockPlex = await startMockPlexServer();
		miru = await startMiruServer({ mockPlex });
		fixture = createTautulliFixture();

		const adminCookie = await miru.apiLogin(E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		await miru.waitForUsers(adminCookie, 2);
		await miru.apiImportTautulli(adminCookie, fixture.filePath, false);

		const users = await miru.apiGetUsers(adminCookie);
		const alice = users.find((u) => u.username === 'alice');
		const bob = users.find((u) => u.username === 'bob');
		if (!alice || !bob) {
			throw new Error(`Expected alice and bob in users list, got: ${JSON.stringify(users)}`);
		}
		aliceMediaUserId = alice.id;
		bobMediaUserId = bob.id;

		// Scoped USER linked only to Alice
		await miru.apiCreateAccount(adminCookie, {
			username: 'scoped_alice',
			password: USER_PASSWORD,
			email: 'scoped-alice@example.test',
			role: 'USER',
			linkedMediaUserIds: [aliceMediaUserId]
		});

		// Unlinked USER with no media user links
		await miru.apiCreateAccount(adminCookie, {
			username: 'unlinked_user',
			password: USER_PASSWORD,
			email: 'unlinked@example.test',
			role: 'USER',
			linkedMediaUserIds: []
		});
	});

	test.afterAll(async () => {
		fixture?.cleanup();
		await miru?.stop();
		await mockPlex?.stop();
	});

	test('ADMIN sees all users, profiles, and admin console', async ({ page }) => {
		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		const nav = page.getByRole('navigation', { name: 'Primary navigation' });
		await expect(nav.getByRole('link', { name: 'Admin' })).toBeVisible();

		await nav.getByRole('link', { name: 'Users' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/users`);
		await expect(page.getByRole('link', { name: /Alice Admin/ })).toBeVisible();
		await expect(page.getByRole('link', { name: /Bob Viewer/ })).toBeVisible();

		// Visit Bob's profile as admin
		await page.getByRole('link', { name: /Bob Viewer/ }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/users/${bobMediaUserId}`);
		await expect(page.getByRole('heading', { name: 'Bob Viewer' })).toBeVisible();
		await expect(page.getByText('Avg Completion')).toBeVisible();
		await expect(page.getByText('Longest Binge Streak')).toBeVisible();
		await expect(page.getByRole('heading', { name: 'Most Rewatched' })).toBeVisible();
		await expect(page.getByRole('heading', { name: 'Year in Review (Wrapped)' })).toBeVisible();
		await page.getByRole('button', { name: 'Open Wrapped Summary' }).click();
		await expect(page.getByRole('dialog')).toContainText('Monthly Activity');
		await page.keyboard.press('Escape');
		await expect(page.getByRole('heading', { name: 'Recent Plays' })).toBeVisible();

		// Visit /admin as admin
		await nav.getByRole('link', { name: 'Admin' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/admin`);
		await expect(page.getByRole('heading', { name: 'Admin & Access Control' })).toBeVisible();
	});

	test('scoped USER has hidden /users nav, blocked routes and /api/v1/users, and scoped history', async ({ page }) => {
		await loginViaUi(page, miru.baseUrl, 'scoped_alice', USER_PASSWORD);
		const nav = page.getByRole('navigation', { name: 'Primary navigation' });

		// Users and Admin nav links are hidden for USER role
		await expect(nav.getByRole('link', { name: 'Admin' })).toHaveCount(0);
		await expect(nav.getByRole('link', { name: 'Users' })).toHaveCount(0);

		// Direct navigation to /admin, /users, and /users/{id} redirects away to /
		for (const protectedPath of [
			'/admin',
			'/users',
			`/users/${aliceMediaUserId}`,
			`/users/${bobMediaUserId}`
		]) {
			await page.goto(`${miru.baseUrl}${protectedPath}`);
			await expect(page).toHaveURL(`${miru.baseUrl}/`);
		}

		// Direct API call to GET /api/v1/users returns 403 Forbidden
		const apiUsersResp = await page.request.get(`${miru.baseUrl}/api/v1/users`);
		expect(apiUsersResp.status()).toBe(403);

		// History is scoped exclusively to Alice's 17 plays and hides the user filter dropdown
		await nav.getByRole('link', { name: 'History' }).click();
		await expect(page.getByText('17 PLAYS')).toBeVisible();
		await expect(page.getByLabel('Filter by user')).toHaveCount(0);
		await expect(page.locator('table tbody')).not.toContainText(/\bbob\b/);
	});

	test('unlinked USER sees clear unlinked empty state across Live, History, and Libraries', async ({ page }) => {
		await loginViaUi(page, miru.baseUrl, 'unlinked_user', USER_PASSWORD);
		const nav = page.getByRole('navigation', { name: 'Primary navigation' });
		await expect(nav.getByRole('link', { name: 'Admin' })).toHaveCount(0);
		await expect(nav.getByRole('link', { name: 'Users' })).toHaveCount(0);

		await expect(page.getByText('No media user linked to your account')).toBeVisible();
		await expect(page.getByText(/administrator must link your media user/i)).toBeVisible();

		await page.goto(`${miru.baseUrl}/users`);
		await expect(page).toHaveURL(`${miru.baseUrl}/`);
		await page.goto(`${miru.baseUrl}/admin`);
		await expect(page).toHaveURL(`${miru.baseUrl}/`);

		await nav.getByRole('link', { name: 'History' }).click();
		await expect(page.getByText('0 PLAYS')).toBeVisible();
		await expect(page.getByText('No media user linked to your account')).toBeVisible();

		await nav.getByRole('link', { name: 'Libraries' }).click();
		await expect(page.getByText('No media user linked to your account')).toBeVisible();
	});
});
