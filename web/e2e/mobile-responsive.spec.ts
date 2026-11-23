import { expect, test } from '@playwright/test';
import { startMockPlexServer, type MockPlexServer, type MockPlexSession } from './helpers/mock-plex';
import {
	E2E_ADMIN_PASSWORD,
	E2E_ADMIN_USERNAME,
	loginViaUi,
	startMiruServer,
	type MiruServer
} from './helpers/miru-server';
import { createTautulliFixture, type TautulliFixture } from './helpers/tautulli-fixture';

test.use({
	viewport: { width: 390, height: 844 },
	hasTouch: true,
	isMobile: true
});

test.describe('Responsive mobile viewport: bottom navigation, compact cards & user preferences menu', () => {
	let mockPlex: MockPlexServer;
	let miru: MiruServer;
	let fixture: TautulliFixture;

	test.beforeAll(async () => {
		mockPlex = await startMockPlexServer();
		miru = await startMiruServer({ mockPlex });
		await mockPlex.waitForWsClient();
		fixture = createTautulliFixture();

		const adminCookie = await miru.apiLogin(E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		await miru.waitForUsers(adminCookie, 2);
		await miru.apiImportTautulli(adminCookie, fixture.filePath, false);
	});

	test.afterAll(async () => {
		fixture?.cleanup();
		await miru?.stop();
		await mockPlex?.stop();
	});

	test('uses mobile bottom navigation, compact history cards, and mobile header user menu preferences/logout', async ({
		page
	}) => {
		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);

		const desktopNav = page.getByRole('navigation', { name: 'Primary navigation' });
		const mobileNav = page.getByRole('navigation', { name: 'Mobile navigation' });
		const mobileHeader = page.locator('header');

		await expect(desktopNav).toBeHidden();
		await expect(mobileNav).toBeVisible();
		await expect(mobileHeader).toBeVisible();

		// Verify bottom nav destinations work on mobile
		await mobileNav.getByRole('link', { name: 'History' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/history`);
		await expect(mobileNav.getByRole('link', { name: 'History' })).toHaveAttribute('aria-current', 'page');

		// On mobile (< md), desktop table is hidden and compact cards + Details button are visible
		await expect(page.locator('table')).toBeHidden();
		await expect(page.getByText('30 PLAYS')).toBeVisible();
		await page.getByRole('button', { name: 'Details', exact: true }).first().click();
		const sheetDialog = page.getByRole('dialog');
		await expect(sheetDialog.getByText('Play Details')).toBeVisible();
		await page.keyboard.press('Escape');
		await expect(sheetDialog).not.toBeVisible();

		// Navigate across Users, Libraries, Admin, and back to Live via mobile bottom nav
		await mobileNav.getByRole('link', { name: 'Users' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/users`);
		await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible();

		await mobileNav.getByRole('link', { name: 'Libraries' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/libraries`);
		await expect(page.getByRole('heading', { name: 'Libraries' })).toBeVisible();

		await mobileNav.getByRole('link', { name: 'Admin' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/admin`);
		await expect(page.getByRole('heading', { name: 'Admin & Access Control' })).toBeVisible();

		await mobileNav.getByRole('link', { name: 'Live' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/`);

		// Open mobile header user preferences menu
		const userMenuBtn = mobileHeader.getByRole('button', { name: 'Open user preferences' });
		await expect(userMenuBtn).toBeVisible();
		await expect(userMenuBtn).toContainText(E2E_ADMIN_USERNAME);
		await userMenuBtn.click();

		const prefsDialog = page.locator('dialog[aria-labelledby="user-preferences-title"]');
		await expect(prefsDialog).toBeVisible();
		await expect(prefsDialog.getByRole('heading', { name: E2E_ADMIN_USERNAME })).toBeVisible();
		await expect(prefsDialog).toContainText('ADMIN');

		// Update Theme to Light & Time Format to 12-hour, save, and verify persistence across reload
		await prefsDialog.getByRole('radiogroup', { name: 'Color theme' }).getByRole('radio', { name: 'Light' }).click();
		await prefsDialog.getByRole('radiogroup', { name: 'Time format' }).getByRole('radio', { name: '12-hour' }).click();
		await prefsDialog.getByRole('button', { name: 'Save Preferences' }).click();
		await expect(prefsDialog).not.toBeVisible();

		await expect
			.poll(() => page.evaluate(() => document.documentElement.classList.contains('dark')))
			.toBe(false);

		await page.reload();
		await mobileHeader.getByRole('button', { name: 'Open user preferences' }).click();
		await expect(prefsDialog).toBeVisible();
		await expect(
			prefsDialog.getByRole('radiogroup', { name: 'Color theme' }).getByRole('radio', { name: 'Light' })
		).toHaveAttribute('aria-checked', 'true');
		await expect(
			prefsDialog.getByRole('radiogroup', { name: 'Time format' }).getByRole('radio', { name: '12-hour' })
		).toHaveAttribute('aria-checked', 'true');

		// Sign out from mobile user menu
		await prefsDialog.getByRole('button', { name: 'Sign Out' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/login`);
	});

	test('allows admin to terminate an active stream via bottom sheet on mobile viewport', async ({ page }) => {
		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);

		mockPlex.clearTerminatedSessions();
		const mobileSession: MockPlexSession = {
			sessionKey: 'mob-sess-1',
			sessionId: 'mob-plex-1',
			ratingKey: '102',
			type: 'movie',
			title: 'Akira',
			year: 1988,
			durationMs: 7_440_000,
			viewOffsetMs: 600_000,
			state: 'playing',
			user: { id: '1001', title: 'alice' },
			player: {
				machineIdentifier: 'ios-mobile',
				product: 'Plex for iOS',
				platform: 'iOS',
				device: 'iPhone',
				title: 'Alice iPhone',
				address: '192.168.1.99',
				local: true
			},
			bandwidthKbps: 12_400,
			location: 'lan',
			decision: 'direct_play',
			videoResolution: '1080',
			videoCodec: 'h264',
			audioCodec: 'dts'
		};

		mockPlex.setActiveSessions([mobileSession]);
		mockPlex.pushPlayingNotification(mobileSession.sessionKey, mobileSession.ratingKey, 'playing', mobileSession.viewOffsetMs);

		const streamCard = page.locator('article').filter({ hasText: 'Akira' });
		await expect(streamCard).toBeVisible();

		await streamCard.getByRole('button', { name: 'Terminate' }).click();
		const bottomSheet = page.getByRole('dialog');
		await expect(bottomSheet.getByText('Terminate Active Stream')).toBeVisible();

		await bottomSheet.getByPlaceholder(/Optional message shown/i).fill('Mobile maintenance stop');
		await bottomSheet.getByRole('button', { name: 'Terminate Stream' }).click();
		await expect(bottomSheet).not.toBeVisible();

		await expect(page.getByRole('status')).toContainText('Termination requested for Alice Admin — Akira');
		await expect(page.getByRole('heading', { name: 'Active Streams (0)' })).toBeVisible();
		expect(mockPlex.getTerminatedSessions()).toEqual([
			{ sessionId: 'mob-plex-1', reason: 'Mobile maintenance stop' }
		]);
	});
});
