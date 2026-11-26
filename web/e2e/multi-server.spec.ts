import { expect, test } from '@playwright/test';
import { startMockJellyfinServer, type MockJellyfinServer, type MockJellyfinSession } from './helpers/mock-jellyfin';
import { startMockPlexServer, type MockPlexServer, type MockPlexSession } from './helpers/mock-plex';
import {
	E2E_ADMIN_PASSWORD,
	E2E_ADMIN_USERNAME,
	loginViaUi,
	startMiruServer,
	type MiruServer
} from './helpers/miru-server';

const SCOPED_USER_PASSWORD = 'MultiServerUser123!';

test.describe.serial('Multi-server Plex + Jellyfin E2E (live SSE, artwork proxy, filters, termination, admin readiness & RBAC)', () => {
	let mockPlex: MockPlexServer;
	let mockJellyfin: MockJellyfinServer;
	let miru: MiruServer;

	test.beforeAll(async () => {
		mockPlex = await startMockPlexServer({ serverName: 'Miru Lab Plex' });
		mockJellyfin = await startMockJellyfinServer({ serverName: 'Studio Jellyfin' });
		miru = await startMiruServer({ mockPlex, mockJellyfin });

		await Promise.all([mockPlex.waitForWsClient(), mockJellyfin.waitForWsClient()]);

		const adminCookie = await miru.apiLogin(E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		await miru.waitForUsers(adminCookie, 5);
		await miru.apiTriggerLibrarySync(adminCookie, true);
		await miru.waitForLibraries(adminCookie, 4);
	});

	test.afterAll(async () => {
		await miru?.stop();
		await mockJellyfin?.stop();
		await mockPlex?.stop();
	});

	test('shows unified Plex & Jellyfin live streams with artwork proxy, server filter bar, and terminates Jellyfin stream live', async ({
		page
	}) => {
		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		await expect(page.getByText('SSE connected')).toBeVisible();

		const plexSession: MockPlexSession = {
			sessionKey: 'ms-plex-1',
			sessionId: 'ms-plex-id-1',
			ratingKey: '101',
			type: 'movie',
			title: 'Spirited Away',
			year: 2001,
			durationMs: 7_500_000,
			viewOffsetMs: 1_200_000,
			state: 'playing',
			user: { id: '1001', title: 'alice' },
			player: {
				machineIdentifier: 'appletv-ms',
				product: 'Plex for Apple TV',
				platform: 'tvOS',
				device: 'Apple TV',
				title: 'Living Room Apple TV',
				address: '192.168.1.42',
				local: true
			},
			bandwidthKbps: 12_000,
			location: 'lan',
			decision: 'direct_play',
			videoResolution: '4k',
			videoCodec: 'hevc',
			audioCodec: 'eac3'
		};

		const jfSession: MockJellyfinSession = {
			sessionId: 'ms-jf-1',
			itemId: 'jf-m1',
			type: 'Movie',
			title: 'Arrival',
			year: 2016,
			durationMs: 6_960_000,
			positionMs: 1_800_000,
			paused: false,
			userId: 'jf-u-alice',
			userName: 'alice',
			client: 'Jellyfin Web',
			deviceName: 'Firefox Desktop',
			deviceId: 'jf-dev-ms-1',
			remoteEndPoint: '203.0.113.15',
			playMethod: 'Transcode',
			hwAccel: 'vaapi',
			bitrateKbps: 8_500,
			videoWidth: 3840,
			videoHeight: 2160,
			videoCodec: 'hevc',
			audioCodec: 'truehd'
		};

		mockJellyfin.clearTerminatedSessions();
		mockPlex.setActiveSessions([plexSession]);
		mockJellyfin.setActiveSessions([jfSession]);
		mockPlex.pushPlayingNotification(plexSession.sessionKey, plexSession.ratingKey, 'playing', plexSession.viewOffsetMs);
		mockJellyfin.pushSessionsUpdate();

		await expect(page.getByRole('heading', { name: 'Active Streams (2)' })).toBeVisible({ timeout: 8_000 });
		const plexCard = page.locator('article').filter({ hasText: 'Spirited Away' });
		const jfCard = page.locator('article').filter({ hasText: 'Arrival' });
		await expect(plexCard).toBeVisible();
		await expect(jfCard).toBeVisible();
		await expect(plexCard).toContainText('PLEX · Miru Lab Plex');
		await expect(jfCard).toContainText('JELLYFIN · Studio Jellyfin');
		await expect(jfCard).toContainText('Transcode');

		// Verify Jellyfin poster image actually loaded through Miru's artwork proxy
		const jfPosterImg = jfCard.locator('img').first();
		await expect(jfPosterImg).toBeVisible();
		await expect
			.poll(async () => jfPosterImg.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0))
			.toBe(true);

		// Filter live streams with ServerFilterBar
		const serverFilter = page.getByRole('group', { name: 'Filter by media server' });
		await expect(serverFilter).toBeVisible();

		await serverFilter.getByRole('button', { name: /Studio Jellyfin/i }).click();
		await expect(jfCard).toBeVisible();
		await expect(plexCard).toHaveCount(0);

		await serverFilter.getByRole('button', { name: /Miru Lab Plex/i }).click();
		await expect(plexCard).toBeVisible();
		await expect(jfCard).toHaveCount(0);

		await serverFilter.getByRole('button', { name: 'All Servers' }).click();
		await expect(plexCard).toBeVisible();
		await expect(jfCard).toBeVisible();

		// Terminate Jellyfin active stream via UI sheet and verify backend calls /Message + /Playing/Stop
		await jfCard.getByRole('button', { name: 'Terminate' }).click();
		const dialog = page.getByRole('dialog');
		await expect(dialog.getByText('Terminate Active Stream')).toBeVisible();
		await dialog.getByPlaceholder(/Optional message shown/i).fill('Please lower Jellyfin bitrate');
		await dialog.getByRole('button', { name: 'Terminate Stream' }).click();
		await expect(dialog).not.toBeVisible();

		await expect(page.getByRole('heading', { name: 'Active Streams (1)' })).toBeVisible({ timeout: 6_000 });
		await expect(jfCard).toHaveCount(0);
		expect(mockJellyfin.getTerminatedSessions()).toEqual([
			{ sessionId: 'ms-jf-1', message: 'Please lower Jellyfin bitrate' }
		]);

		// Stop Plex stream too so both plays are recorded in history for the next test
		mockPlex.setActiveSessions([]);
		mockPlex.pushPlayingNotification(plexSession.sessionKey, plexSession.ratingKey, 'stopped', 2_000_000);
		await expect(page.getByRole('heading', { name: 'Active Streams (0)' })).toBeVisible({ timeout: 6_000 });
	});

	test('filters History, Libraries (with Jellyfin MediaDetailsModal), and Users across both servers', async ({ page }) => {
		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		const nav = page.getByRole('navigation', { name: 'Primary navigation' });

		// 1. History server filtering
		await nav.getByRole('link', { name: 'History' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/history`);
		await expect(page.locator('table tbody')).toContainText('Spirited Away');
		await expect(page.locator('table tbody')).toContainText('Arrival');

		const historyFilter = page.getByRole('group', { name: 'Filter by media server' });
		await historyFilter.getByRole('button', { name: /Studio Jellyfin/i }).click();
		await expect(page.locator('table tbody')).toContainText('Arrival');
		await expect(page.locator('table tbody')).not.toContainText('Spirited Away');

		await historyFilter.getByRole('button', { name: /Miru Lab Plex/i }).click();
		await expect(page.locator('table tbody')).toContainText('Spirited Away');
		await expect(page.locator('table tbody')).not.toContainText('Arrival');

		// 2. Libraries server filtering + Jellyfin MediaDetailsModal
		await nav.getByRole('link', { name: 'Libraries' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/libraries`);
		await expect(page.getByRole('button', { name: /Jellyfin Movies/i })).toBeVisible();
		await expect(page.getByText('Movies', { exact: true }).first()).toBeVisible();

		const libFilter = page.getByRole('group', { name: 'Filter by media server' });
		await libFilter.getByRole('button', { name: /Studio Jellyfin/i }).click();
		await expect(page.getByRole('button', { name: /Jellyfin Movies/i })).toBeVisible();
		await expect(page.getByRole('button', { name: /Jellyfin Series/i })).toBeVisible();
		await expect(page.getByText('TV Shows', { exact: true })).toHaveCount(0);

		await page.getByRole('button', { name: /Jellyfin Movies/i }).click();
		await expect(page.getByRole('button', { name: /Arrival/i }).first()).toBeVisible();
		await page.getByRole('button', { name: /Arrival/i }).first().click();
		const detailsDialog = page.getByRole('dialog');
		await expect(detailsDialog).toBeVisible();
		await expect(detailsDialog).toContainText('Arrival');
		await expect(detailsDialog).toContainText('Denis Villeneuve');
		await expect(detailsDialog).toContainText('JELLYFIN');
		await page.keyboard.press('Escape');

		// 3. Users server filtering (both Plex alice and Jellyfin alice present and disambiguated)
		await nav.getByRole('link', { name: 'Users' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/users`);
		await expect(page.getByRole('link', { name: /diana/i })).toBeVisible();
		await expect(page.getByRole('link', { name: /@alice/i })).toHaveCount(2);

		const usersFilter = page.getByRole('group', { name: 'Filter by media server' });
		await usersFilter.getByRole('button', { name: /Studio Jellyfin/i }).click();
		await expect(page.getByRole('link', { name: /diana/i })).toBeVisible();
		await expect(page.getByRole('link', { name: /@alice/i })).toHaveCount(1);
		await expect(page.getByRole('link', { name: /Bob Viewer/i })).toHaveCount(0);
	});

	test('shows dual-server WS readiness in Admin and enforces RBAC disambiguation for identically named Plex & Jellyfin users', async ({
		page,
		browser
	}) => {
		const adminCookie = await miru.apiLogin(E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		const users = await miru.apiGetUsers(adminCookie);
		const jfAlice = users.find((u) => u.username === 'alice' && u.serverType === 'jellyfin');
		const plexAlice = users.find((u) => u.username === 'alice' && u.serverType === 'plex');
		expect(jfAlice).toBeTruthy();
		expect(plexAlice).toBeTruthy();
		expect(jfAlice!.id).not.toBe(plexAlice!.id);

		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		const nav = page.getByRole('navigation', { name: 'Primary navigation' });
		await nav.getByRole('link', { name: 'Admin' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/admin`);

		// Verify both Plex and Jellyfin cards appear with WS CONNECTED and ENV OVERRIDE
		const serversSection = page.locator('section').filter({ hasText: 'Media Servers & Readiness' });
		const plexCard = serversSection.locator('> div > div').filter({ hasText: 'Miru Lab Plex' });
		const jfCard = serversSection.locator('> div > div').filter({ hasText: 'Studio Jellyfin' });
		await expect(plexCard).toContainText('PLEX');
		await expect(plexCard).toContainText('ENV OVERRIDE');
		await expect(plexCard).toContainText('WS CONNECTED');
		await expect(jfCard).toContainText('JELLYFIN');
		await expect(jfCard).toContainText('ENV OVERRIDE');
		await expect(jfCard).toContainText('WS CONNECTED');

		// Create scoped USER linked ONLY to Jellyfin's alice (leaving Plex's alice unlinked)
		await miru.apiCreateAccount(adminCookie, {
			username: 'jf_only_alice',
			password: SCOPED_USER_PASSWORD,
			role: 'USER',
			linkedMediaUserIds: [jfAlice!.id]
		});

		await page.reload();
		const accountsSection = page.locator('section').filter({ hasText: 'Accounts & Linked Media Users' });
		const jfOnlyRow = accountsSection.locator('> div > div').filter({ hasText: 'jf_only_alice' });
		await expect(jfOnlyRow).toBeVisible();
		await jfOnlyRow.getByRole('button', { name: 'Link Users' }).click();
		const linkDialog = page.getByRole('dialog');
		await expect(linkDialog).toContainText('JELLYFIN');
		await expect(linkDialog).toContainText('PLEX');
		await page.keyboard.press('Escape');
		await expect(linkDialog).not.toBeVisible();

		// Verify jf_only_alice sees ONLY Jellyfin history ("Arrival") and Jellyfin libraries, never Plex ("Spirited Away")
		const scopedCtx = await browser.newContext();
		try {
			const scopedPage = await scopedCtx.newPage();
			await loginViaUi(scopedPage, miru.baseUrl, 'jf_only_alice', SCOPED_USER_PASSWORD);
			const scopedNav = scopedPage.getByRole('navigation', { name: 'Primary navigation' });

			await scopedNav.getByRole('link', { name: 'History' }).click();
			await expect(scopedPage.locator('table tbody')).toContainText('Arrival');
			await expect(scopedPage.locator('table tbody')).not.toContainText('Spirited Away');

			await scopedNav.getByRole('link', { name: 'Libraries' }).click();
			await expect(scopedPage.getByRole('button', { name: /Jellyfin Movies/i })).toBeVisible();
			await expect(scopedPage.getByText('TV Shows', { exact: true })).toHaveCount(0);
		} finally {
			await scopedCtx.close();
		}
	});

	// Runs last: it stops the mock Jellyfin server to simulate an outage
	test('shows per-server status in the sidebar and mobile header, and marks only the server that goes down as Offline', async ({
		page
	}) => {
		test.setTimeout(120_000);
		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);

		const sidebar = page.getByTestId('sidebar-server-status');
		const plexRow = sidebar.locator('li').filter({ hasText: 'Miru Lab Plex' });
		const jfRow = sidebar.locator('li').filter({ hasText: 'Studio Jellyfin' });
		await expect(plexRow).toHaveAttribute('data-status', 'live');
		await expect(jfRow).toHaveAttribute('data-status', 'live');
		await expect(plexRow).toContainText('PLEX');
		await expect(jfRow).toContainText('JELLYFIN');
		await expect(sidebar).toContainText('Live updates');
		await expect(page.getByText(/\d+\/\d+ Servers/)).toHaveCount(0);

		await mockJellyfin.stop();

		// Miru notices on its next Jellyfin poll; the page picks it up on its next status refresh (30 s)
		await expect(jfRow).toHaveAttribute('data-status', 'offline', { timeout: 75_000 });
		await expect(jfRow).toContainText('Offline');
		await expect(plexRow).toHaveAttribute('data-status', 'live');
		await expect(sidebar).toContainText('SSE');

		await page.setViewportSize({ width: 375, height: 800 });
		const mobileTrigger = page.getByRole('button', { name: /^Media server status:/ });
		await expect(mobileTrigger).toHaveAttribute(
			'aria-label',
			'Media server status: Miru Lab Plex Live, Studio Jellyfin Offline'
		);
		await mobileTrigger.click();
		const popoverList = page.getByRole('dialog').getByRole('list', { name: 'Media server status' });
		await expect(popoverList.locator('li[data-status="offline"]')).toContainText('Studio Jellyfin');
		await expect(popoverList.locator('li[data-status="live"]')).toContainText('Miru Lab Plex');
	});
});
