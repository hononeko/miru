import { expect, test } from '@playwright/test';
import { startMockJellyfinServer, type MockJellyfinServer, type MockJellyfinSession } from './helpers/mock-jellyfin';
import { startMockPlexServer, type MockPlexServer } from './helpers/mock-plex';
import { E2E_ADMIN_PASSWORD, E2E_ADMIN_USERNAME, loginViaUi, startMiruServer, type MiruServer } from './helpers/miru-server';

test.describe.serial('Admin server disconnect & remove flow (MRU-60 + MRU-64)', () => {
	let mockPlex: MockPlexServer;
	let mockJellyfin: MockJellyfinServer;
	let miru: MiruServer;

	test.beforeAll(async () => {
		mockPlex = await startMockPlexServer({ serverName: 'Miru Env Plex' });
		mockJellyfin = await startMockJellyfinServer({ serverName: 'Studio Jellyfin' });
		// Start with env-managed Plex + UI zero-config for Jellyfin (Quick Connect)
		miru = await startMiruServer({ mockPlex });
		await mockPlex.waitForWsClient();
	});

	test.afterAll(async () => {
		await miru?.stop();
		await mockJellyfin?.stop();
		await mockPlex?.stop();
	});

	test('disconnects UI-connected Jellyfin cleanly keeping history, falls back sidebar, and removes data after typed confirmation', async ({
		page
	}) => {
		test.setTimeout(120_000);
		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);

		const nav = page.getByRole('navigation', { name: 'Primary navigation' });
		await nav.getByRole('link', { name: 'Admin' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/admin`);

		// Env-managed Plex card has ENV MANAGED badge and no Disconnect/Remove buttons
		const serversSection = page.locator('section').filter({ hasText: 'Media Servers & Readiness' });
		const plexCard = serversSection.locator('> div > div').filter({ hasText: 'Miru Env Plex' });
		await expect(plexCard).toContainText('ENV MANAGED');
		await expect(plexCard.getByRole('button', { name: 'Disconnect' })).toHaveCount(0);
		await expect(plexCard.getByRole('button', { name: 'Remove' })).toHaveCount(0);

		// Connect Jellyfin via Quick Connect from the Admin UI
		const jfCard = serversSection.locator('> div > div').filter({ hasText: 'JELLYFIN' }).first();
		await jfCard.getByRole('button', { name: 'Connect Jellyfin' }).click();
		const dialog = page.getByRole('dialog');
		await dialog.getByLabel('Jellyfin Server URL').fill(mockJellyfin.baseUrl);
		await dialog.getByRole('button', { name: 'Connect', exact: true }).click();
		await expect(dialog.getByTestId('jellyfin-qc-code')).toBeVisible();
		mockJellyfin.approveQuickConnect({ isAdministrator: true, userName: 'alice' });
		await expect(dialog.getByText('Studio Jellyfin Connected')).toBeVisible();
		await dialog.getByRole('button', { name: 'Done' }).click();
		await expect(dialog).not.toBeVisible();

		await mockJellyfin.waitForWsClient(15_000);
		const cookie = await miru.apiLogin(E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		await miru.waitForLibraries(cookie, 4);

		// Multi-server sidebar indicator appears when both Plex & Jellyfin are configured
		const sidebarStatus = page.getByTestId('sidebar-server-status');
		await expect(sidebarStatus).toBeVisible();
		await expect(sidebarStatus).toContainText('Studio Jellyfin');

		// Start a live Jellyfin stream and verify MRU-64 episode details modal works
		const jfEpisodeSession: MockJellyfinSession = {
			sessionId: 'e2e-jf-disc-sess',
			itemId: 'jf-ep1',
			type: 'Episode',
			title: 'Dulcinea',
			seriesId: 'jf-show1',
			seriesName: 'The Expanse',
			seasonId: 'jf-season1',
			seasonName: 'Season 1',
			parentIndexNumber: 1,
			indexNumber: 1,
			year: 2015,
			durationMs: 2_820_000,
			positionMs: 300_000,
			paused: false,
			userId: 'jf-u-alice',
			userName: 'alice',
			client: 'Jellyfin Web',
			deviceName: 'Firefox',
			deviceId: 'e2e-jf-dev-disc',
			remoteEndPoint: '192.168.1.90',
			playMethod: 'DirectPlay',
			bitrateKbps: 9_500
		};
		mockJellyfin.setActiveSessions([jfEpisodeSession]);
		mockJellyfin.pushSessionsUpdate();

		await nav.getByRole('link', { name: 'Live' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/`);
		const streamCard = page.locator('article').filter({ hasText: 'Dulcinea' });
		await expect(streamCard).toBeVisible({ timeout: 10_000 });

		// Verify episode details modal works (GET /Items?Ids=... not GET /Items/{id})
		await streamCard.getByRole('button', { name: 'View media details for The Expanse' }).click();
		await expect(dialog).toBeVisible();
		await expect(dialog).toContainText('Dulcinea');
		await expect(dialog).toContainText('The Expanse');
		await page.keyboard.press('Escape');
		await expect(dialog).not.toBeVisible();

		// Disconnect Jellyfin from Admin UI
		await nav.getByRole('link', { name: 'Admin' }).click();
		const connectedJfCard = serversSection.locator('> div > div').filter({ hasText: 'Studio Jellyfin' });
		await connectedJfCard.getByRole('button', { name: 'Disconnect' }).click();
		await expect(dialog.getByRole('heading', { name: 'Disconnect · Studio Jellyfin' })).toBeVisible();
		await expect(dialog).toContainText('Playback history, users, and libraries are kept');
		await dialog.getByRole('button', { name: 'Disconnect Server' }).click();
		await expect(dialog).not.toBeVisible();

		// API key revoked remotely & multi-server sidebar fell back to single-server
		expect(mockJellyfin.getRevokedApiKeys()).toEqual(mockJellyfin.getIssuedApiKeys());
		await expect(connectedJfCard).toContainText('DISCONNECTED');
		await expect(sidebarStatus).toHaveCount(0);

		// Live stream is gone from Live page
		await nav.getByRole('link', { name: 'Live' }).click();
		await expect(page.getByRole('heading', { name: 'Active Streams (0)' })).toBeVisible();

		// Closed Jellyfin play reached History and is labelled Disconnected
		await nav.getByRole('link', { name: 'History' }).click();
		const dulcineaRow = page.locator('table tbody tr').filter({ hasText: 'Dulcinea' });
		await expect(dulcineaRow).toBeVisible();
		await expect(dulcineaRow).toContainText('JELLYFIN · Studio Jellyfin · Disconnected');

		// Libraries & Users still preserve Jellyfin items labelled with Disconnected server badge
		await nav.getByRole('link', { name: 'Libraries' }).click();
		await expect(page.getByRole('button', { name: /Jellyfin Movies/i })).toContainText('JELLYFIN · Studio Jellyfin · Disconnected');

		await nav.getByRole('link', { name: 'Users' }).click();
		await expect(page.getByRole('link', { name: /diana/i })).toContainText('JELLYFIN · Studio Jellyfin · Disconnected');

		// Remove the disconnected Jellyfin server with typed confirmation
		await nav.getByRole('link', { name: 'Admin' }).click();
		const disconnectedJfCard = serversSection.locator('> div > div').filter({ hasText: 'Studio Jellyfin' });
		await disconnectedJfCard.getByRole('button', { name: 'Remove' }).click();
		await expect(dialog.getByRole('heading', { name: 'Remove Server & Data · Studio Jellyfin' })).toBeVisible();
		const confirmBtn = dialog.getByRole('button', { name: 'Remove Server & Data' });
		await expect(confirmBtn).toBeDisabled();
		await dialog.getByPlaceholder('Studio Jellyfin').fill('Studio Jellyfin');
		await expect(confirmBtn).toBeEnabled();
		await confirmBtn.click();
		await expect(dialog).not.toBeVisible();

		await expect(serversSection.locator('> div > div').filter({ hasText: 'Studio Jellyfin' })).toHaveCount(0);

		// Jellyfin history, users, and libraries are permanently removed while Plex remains intact
		await nav.getByRole('link', { name: 'History' }).click();
		await expect(page.locator('table tbody tr').filter({ hasText: 'Dulcinea' })).toHaveCount(0);

		await nav.getByRole('link', { name: 'Libraries' }).click();
		await expect(page.getByRole('button', { name: /Jellyfin Movies/i })).toHaveCount(0);
		await expect(page.getByRole('button', { name: /Movies/i })).toBeVisible();
	});
});
