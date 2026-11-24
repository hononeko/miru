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

test.describe('Playback History filtering, search, pagination, sorting & detail drawer', () => {
	let mockPlex: MockPlexServer;
	let miru: MiruServer;
	let fixture: TautulliFixture;

	test.beforeAll(async () => {
		mockPlex = await startMockPlexServer();
		miru = await startMiruServer({ mockPlex });
		fixture = createTautulliFixture();

		const cookie = await miru.apiLogin(E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		await miru.waitForUsers(cookie, 2);
		await miru.apiImportTautulli(cookie, fixture.filePath, false);
	});

	test.afterAll(async () => {
		fixture?.cleanup();
		await miru?.stop();
		await mockPlex?.stop();
	});

	test('paginates, searches, filters, sorts, opens play detail drawer and media details modal', async ({ page }) => {
		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		await page.getByRole('navigation', { name: 'Primary navigation' }).getByRole('link', { name: 'History' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/history`);

		// Initial 30 plays paginated across 2 pages (25 + 5)
		await expect(page.getByText('30 PLAYS')).toBeVisible();
		await expect(page.getByText('Page 1 of 2 · 30 total')).toBeVisible();
		const desktopRows = page.locator('table tbody tr');
		await expect(desktopRows).toHaveCount(25);

		// Pagination Next -> Page 2 (5 rows) -> Prev -> Page 1
		await page.getByRole('button', { name: 'Next' }).click();
		await expect(page.getByText('Page 2 of 2 · 30 total')).toBeVisible();
		await expect(desktopRows).toHaveCount(5);
		await expect(page.getByRole('button', { name: 'Next' })).toBeDisabled();

		await page.getByRole('button', { name: 'Prev' }).click();
		await expect(page.getByText('Page 1 of 2 · 30 total')).toBeVisible();
		await expect(desktopRows).toHaveCount(25);

		// Search filter
		await page.getByLabel('Search playback history').fill('Spirited');
		await expect(page.getByText('6 PLAYS')).toBeVisible();
		await expect(desktopRows).toHaveCount(6);
		await expect(desktopRows.first()).toContainText('Spirited Away');

		// Reset sits inside the search bar, on its right side, and clears the search
		const searchBox = (await page.getByLabel('Search playback history').boundingBox())!;
		const resetBox = (await page.getByRole('button', { name: 'Reset' }).boundingBox())!;
		expect(resetBox.x).toBeGreaterThan(searchBox.x + searchBox.width / 2);
		expect(resetBox.x + resetBox.width).toBeLessThanOrEqual(searchBox.x + searchBox.width);
		expect(resetBox.y).toBeGreaterThanOrEqual(searchBox.y);
		expect(resetBox.y + resetBox.height).toBeLessThanOrEqual(searchBox.y + searchBox.height);
		await page.getByRole('button', { name: 'Reset' }).click();
		await expect(page.getByLabel('Search playback history')).toHaveValue('');
		await expect(page.getByText('30 PLAYS')).toBeVisible();
		await expect(page.getByRole('button', { name: 'Reset' })).toHaveCount(0);

		// Filter by User (Bob Viewer has 13 plays)
		const userSelect = page.getByLabel('Filter by user');
		const bobOptionValue = await userSelect.locator('option', { hasText: 'Bob Viewer' }).getAttribute('value');
		expect(bobOptionValue).toBeTruthy();
		await userSelect.selectOption(bobOptionValue!);
		await expect(page.getByText('13 PLAYS')).toBeVisible();
		await expect(desktopRows).toHaveCount(13);

		// Combine with Media Type filter (Bob has 4 episodes: 2 Good News + 2 Half Loop)
		await page.getByLabel('Filter by media type').selectOption('episode');
		await expect(page.getByText('4 PLAYS')).toBeVisible();
		await expect(desktopRows).toHaveCount(4);

		// Combine with Stream Decision filter (all 4 episodes are transcodes; Direct Play gives 0)
		await page.getByLabel('Filter by stream decision').selectOption('direct_play');
		await expect(page.locator('table').getByText('No playback records match your current filters.')).toBeVisible();

		await page.getByLabel('Filter by stream decision').selectOption('transcode');
		await expect(page.getByText('4 PLAYS')).toBeVisible();

		// Reset all filters
		await page.getByRole('button', { name: 'Reset' }).click();
		await expect(page.getByText('30 PLAYS')).toBeVisible();

		// Column headers sort by one column at a time; clicking the active one flips its direction
		const header = (name: string) => page.getByRole('columnheader', { name, exact: true });
		const sortedColumns = page.locator('th[aria-sort="ascending"], th[aria-sort="descending"]');
		await expect(header('When')).toHaveAttribute('aria-sort', 'descending');

		await header('Duration').getByRole('button').click();
		await expect(header('Duration')).toHaveAttribute('aria-sort', 'descending');
		await expect(header('When')).toHaveAttribute('aria-sort', 'none');
		await expect(sortedColumns).toHaveCount(1);

		await page.getByRole('button', { name: 'Next' }).click();
		await expect(page.getByText('Page 2 of 2 · 30 total')).toBeVisible();
		await header('Media').getByRole('button').click();
		await expect(page.getByText('Page 1 of 2 · 30 total')).toBeVisible();
		await expect(header('Media')).toHaveAttribute('aria-sort', 'ascending');
		await expect(sortedColumns).toHaveCount(1);
		await expect(desktopRows.first()).toContainText('Akira');

		await header('Media').getByRole('button').click();
		await expect(header('Media')).toHaveAttribute('aria-sort', 'descending');
		await expect(desktopRows.first()).toContainText('Spirited Away');

		await header('Media').getByRole('button').click();
		await expect(header('Media')).toHaveAttribute('aria-sort', 'ascending');
		await expect(desktopRows.first()).toContainText('Akira');

		// A slow response for an earlier sort does not replace the rows of the current one
		await page.route(/\/api\/v1\/history\?.*sortBy=title&sortDir=desc/, async (route) => {
			await new Promise((resolve) => setTimeout(resolve, 1_000));
			await route.continue();
		});
		const slowResponse = page.waitForResponse(/sortBy=title&sortDir=desc/);
		await header('Media').getByRole('button').click();
		await header('Media').getByRole('button').click();
		await expect(header('Media')).toHaveAttribute('aria-sort', 'ascending');
		await slowResponse;
		await page.waitForTimeout(200);
		await expect(desktopRows.first()).toContainText('Akira');
		await page.unrouteAll();

		// Open Play Detail Drawer by clicking the first row (non-title cell)
		await desktopRows.first().locator('td').first().click();
		const sheetDialog = page.getByRole('dialog');
		await expect(sheetDialog.getByText('Play Details')).toBeVisible();
		await expect(sheetDialog).toContainText('Akira');
		await expect(sheetDialog).toContainText(/Direct Stream/i);
		await page.keyboard.press('Escape');
		await expect(sheetDialog).not.toBeVisible();

		// Open Media Details Modal by clicking the media title button inside the row
		await desktopRows.first().getByRole('button', { name: 'Akira' }).click();
		const mediaDialog = page.locator('dialog.media-details-dialog');
		await expect(mediaDialog).toBeVisible();
		await expect(mediaDialog.getByRole('heading', { name: 'Akira' })).toBeVisible();
		await expect(mediaDialog).toContainText('Katsuhiro Otomo');
		await expect(mediaDialog).toContainText('Miru Play Summary');
		await mediaDialog.getByRole('button', { name: 'Close', exact: true }).click();
		await expect(mediaDialog).not.toBeVisible();
	});
});
