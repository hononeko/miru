import { expect, test } from '@playwright/test';
import { startMockPlexServer, type MockPlexServer } from './helpers/mock-plex';
import {
	E2E_ADMIN_PASSWORD,
	E2E_ADMIN_USERNAME,
	loginViaUi,
	startMiruServer,
	type MiruServer
} from './helpers/miru-server';

const VIEWER_PASSWORD = 'LibraryViewer123!';

test.describe('Libraries browsing, admin-triggered deep sync & media details', () => {
	let mockPlex: MockPlexServer;
	let miru: MiruServer;
	let adminCookie: string;

	test.beforeAll(async () => {
		mockPlex = await startMockPlexServer();
		miru = await startMiruServer({ mockPlex });

		adminCookie = await miru.apiLogin(E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		await miru.waitForLibraries(adminCookie, 2);
		const users = await miru.waitForUsers(adminCookie, 2);
		const bob = users.find((u) => u.username === 'bob');
		if (!bob) {
			throw new Error(`Expected bob in users list, got: ${JSON.stringify(users)}`);
		}
		await miru.apiTriggerLibrarySync(adminCookie, false);

		await miru.apiCreateAccount(adminCookie, {
			username: 'bob_viewer',
			password: VIEWER_PASSWORD,
			role: 'USER',
			linkedMediaUserIds: [bob.id]
		});
		await miru.apiCreateAccount(adminCookie, {
			username: 'unlinked_viewer',
			password: VIEWER_PASSWORD,
			role: 'USER',
			linkedMediaUserIds: []
		});
	});

	test.afterAll(async () => {
		await miru?.stop();
		await mockPlex?.stop();
	});

	test('admin triggers deep library sync, browses sections, and inspects media details', async ({ page }) => {
		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		await page.getByRole('navigation', { name: 'Primary navigation' }).getByRole('link', { name: 'Libraries' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/libraries`);

		// Initial fast-synced sections appear
		await expect(page.getByRole('button', { name: /Movies/ })).toBeVisible();
		await expect(page.getByRole('button', { name: /TV Shows/ })).toBeVisible();

		// Admin triggers deep sync
		await page.getByRole('button', { name: 'Sync Libraries' }).click();
		await expect(page.getByText(/Synced 2 sections \(5 items\)/)).toBeVisible();

		// Movies section breakdown, snapshot badge & Recently Added grid populated
		await page.getByRole('button', { name: /Movies/ }).click();
		await expect(page.getByRole('heading', { name: 'Movies · Technical Breakdown' })).toBeVisible();
		await expect(page.getByRole('heading', { name: 'Growth History' })).toBeVisible();
		await expect(page.getByText('No additions or removals recorded yet')).toBeVisible();
		await expect(page.getByRole('heading', { name: 'Recently Added · Movies' })).toBeVisible();
		await expect(page.getByRole('button', { name: /Spirited Away/ })).toBeVisible();
		await expect(page.getByRole('button', { name: /Akira/ })).toBeVisible();
		await expect(page.getByRole('button', { name: /Blade Runner 2049/ })).toBeVisible();

		// Open Movie Media Details modal from Recently Added poster
		await page.getByRole('button', { name: /Spirited Away/ }).click();
		const mediaDialog = page.locator('dialog.media-details-dialog');
		await expect(mediaDialog).toBeVisible();
		await expect(mediaDialog.getByRole('heading', { name: 'Spirited Away' })).toBeVisible();
		await expect(mediaDialog).toContainText('Hayao Miyazaki');
		await expect(mediaDialog).toContainText('Technical Specs');
		await mediaDialog.getByRole('button', { name: 'Close', exact: true }).click();
		await expect(mediaDialog).not.toBeVisible();

		// Switch to TV Shows section and inspect Episode Media Details (with Series Overview & Season Details)
		await page.getByRole('button', { name: /TV Shows/ }).click();
		await expect(page.getByRole('heading', { name: 'TV Shows · Technical Breakdown' })).toBeVisible();
		await expect(page.getByText('1 seasons · 2 episodes')).toBeVisible();
		await expect(page.getByRole('button', { name: /Good News About Hell/ })).toBeVisible();

		await page.getByRole('button', { name: /Good News About Hell/ }).click();
		await expect(mediaDialog).toBeVisible();
		await expect(mediaDialog.getByRole('heading', { name: 'Good News About Hell' })).toBeVisible();
		await expect(mediaDialog.getByRole('heading', { name: 'Series Overview' })).toBeVisible();
		await expect(mediaDialog.getByRole('heading', { name: 'Season Details' })).toBeVisible();
		await expect(mediaDialog).toContainText('Severance');
		await mediaDialog.getByRole('button', { name: 'Close', exact: true }).click();

		// Switch to Hygiene / Stale Media tab and verify threshold & sort controls
		await page.getByRole('button', { name: 'Hygiene / Stale Media' }).click();
		await expect(page.getByText('Reclaimable Storage')).toBeVisible();
		await expect(page.getByRole('heading', { name: 'Stale & Unwatched Media · TV Shows' })).toBeVisible();
		await page.getByRole('group', { name: 'Unwatched threshold' }).getByRole('button', { name: 'Never played' }).click();
		await page.getByRole('group', { name: 'Stale media sort' }).getByRole('button', { name: 'Oldest first' }).click();
		await expect(page.getByRole('button', { name: /Half Loop/ })).toBeVisible();
	});

	test('non-admin USER sees only shared Plex libraries, handles unshared /403 routes, and updates on revocation', async ({ page }) => {
		await loginViaUi(page, miru.baseUrl, 'bob_viewer', VIEWER_PASSWORD);
		await page.getByRole('navigation', { name: 'Primary navigation' }).getByRole('link', { name: 'Libraries' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/libraries`);

		// Bob has Movies (key 1) shared in mockPlex, not TV Shows (key 2)
		await expect(page.getByRole('button', { name: /Movies/ })).toBeVisible();
		await expect(page.getByRole('button', { name: /TV Shows/ })).toHaveCount(0);
		await expect(page.getByRole('button', { name: 'Sync Libraries' })).toHaveCount(0);

		// The hygiene report is for admins only, in the UI and the API
		await expect(page.getByRole('heading', { name: 'Movies · Technical Breakdown' })).toBeVisible();
		await expect(page.getByText('Hygiene / Stale Media')).toHaveCount(0);
		const staleReport = await page.request.get(`${miru.baseUrl}/api/v1/libraries/1/stale`);
		expect(staleReport.status()).toBe(403);

		// Direct navigation to unshared section shows access unavailable instead of crashing
		await page.goto(`${miru.baseUrl}/libraries/2`);
		await expect(page.getByText('Library access unavailable')).toBeVisible();

		// Media details and artwork allow shared section 1 (Movies 101) and forbid unshared section 2 (Episode 202)
		const allowedDetails = await page.request.get(`${miru.baseUrl}/api/v1/media/101`);
		expect(allowedDetails.status()).toBe(200);
		const allowedThumb = await page.request.get(`${miru.baseUrl}/api/v1/media/101/thumb`);
		expect(allowedThumb.status()).toBe(200);

		const forbiddenDetails = await page.request.get(`${miru.baseUrl}/api/v1/media/202`);
		expect(forbiddenDetails.status()).toBe(403);
		const forbiddenThumb = await page.request.get(`${miru.baseUrl}/api/v1/media/202/thumb`);
		expect(forbiddenThumb.status()).toBe(403);

		// Revoke Bob's shared access and trigger admin sync -> USER now sees empty shared-library state
		mockPlex.setSharedServers([]);
		await miru.apiTriggerLibrarySync(adminCookie, false);
		await page.goto(`${miru.baseUrl}/libraries`);
		await expect(page.getByText('No libraries shared with your account')).toBeVisible();
	});

	test('unlinked USER sees unlinked empty state on /libraries', async ({ page }) => {
		await loginViaUi(page, miru.baseUrl, 'unlinked_viewer', VIEWER_PASSWORD);
		await page.getByRole('navigation', { name: 'Primary navigation' }).getByRole('link', { name: 'Libraries' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/libraries`);
		await expect(page.getByText('No media user linked to your account')).toBeVisible();
	});
});
