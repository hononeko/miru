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
		await expect(page.getByRole('heading', { name: 'Recent Plays' })).toBeVisible();

		// Visit /admin as admin
		await nav.getByRole('link', { name: 'Admin' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/admin`);
		await expect(page.getByRole('heading', { name: 'Admin & Access Control' })).toBeVisible();
	});

	test('scoped USER sees only linked media user and is blocked from other users and /admin', async ({ page }) => {
		await loginViaUi(page, miru.baseUrl, 'scoped_alice', USER_PASSWORD);
		const nav = page.getByRole('navigation', { name: 'Primary navigation' });

		// Admin nav link is hidden and direct /admin navigation redirects to /
		await expect(nav.getByRole('link', { name: 'Admin' })).toHaveCount(0);
		await page.goto(`${miru.baseUrl}/admin`);
		await expect(page).toHaveURL(`${miru.baseUrl}/`);

		// /users shows ONLY Alice Admin, never Bob Viewer
		await nav.getByRole('link', { name: 'Users' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/users`);
		await expect(page.getByRole('link', { name: /Alice Admin/ })).toBeVisible();
		await expect(page.getByRole('link', { name: /Bob Viewer/ })).toHaveCount(0);

		// Can view Alice's profile
		await page.getByRole('link', { name: /Alice Admin/ }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/users/${aliceMediaUserId}`);
		await expect(page.getByRole('heading', { name: 'Alice Admin' })).toBeVisible();

		// Direct navigation to Bob's profile is denied (403 error, no Bob profile rendered)
		await page.goto(`${miru.baseUrl}/users/${bobMediaUserId}`);
		await expect(page.getByRole('heading', { name: 'Bob Viewer' })).toHaveCount(0);
		await expect(page.getByText(/forbidden|access|permission|denied|not allowed/i)).toBeVisible();

		// History is scoped exclusively to Alice's 17 plays
		await nav.getByRole('link', { name: 'History' }).click();
		await expect(page.getByText('17 PLAYS')).toBeVisible();
		await expect(page.locator('table tbody')).not.toContainText(/\bbob\b/);
	});

	test('unlinked USER sees zero media users, empty history, and cannot access /admin', async ({ page }) => {
		await loginViaUi(page, miru.baseUrl, 'unlinked_user', USER_PASSWORD);
		const nav = page.getByRole('navigation', { name: 'Primary navigation' });
		await expect(nav.getByRole('link', { name: 'Admin' })).toHaveCount(0);

		await page.goto(`${miru.baseUrl}/admin`);
		await expect(page).toHaveURL(`${miru.baseUrl}/`);

		await nav.getByRole('link', { name: 'Users' }).click();
		await expect(page.getByText('No matching users found.')).toBeVisible();

		await nav.getByRole('link', { name: 'History' }).click();
		await expect(page.getByText('0 PLAYS')).toBeVisible();
		await expect(page.locator('table').getByText('No playback records match your current filters.')).toBeVisible();
	});
});
