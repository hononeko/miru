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

	test.beforeAll(async () => {
		mockPlex = await startMockPlexServer();
		miru = await startMiruServer({ mockPlex });

		const adminCookie = await miru.apiLogin(E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		await miru.waitForLibraries(adminCookie, 2);
		await miru.apiCreateAccount(adminCookie, {
			username: 'library_viewer',
			password: VIEWER_PASSWORD,
			role: 'USER'
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
		await expect(page.getByText('1 SNAPS')).toBeVisible();
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
	});

	test('non-admin USER can browse libraries but cannot trigger sync', async ({ page }) => {
		await loginViaUi(page, miru.baseUrl, 'library_viewer', VIEWER_PASSWORD);
		await page.getByRole('navigation', { name: 'Primary navigation' }).getByRole('link', { name: 'Libraries' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/libraries`);

		await expect(page.getByRole('button', { name: /Movies/ })).toBeVisible();
		await expect(page.getByRole('button', { name: /TV Shows/ })).toBeVisible();
		await expect(page.getByRole('button', { name: 'Sync Libraries' })).toHaveCount(0);
	});
});
