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

test.describe('Live Activity SSE, stream lifecycle, KPI strip & Top Watched ranges', () => {
	let mockPlex: MockPlexServer;
	let miru: MiruServer;
	let fixture: TautulliFixture;

	test.beforeAll(async () => {
		mockPlex = await startMockPlexServer({ serverName: 'Miru Lab Plex' });
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

	test('streams update in real time over SSE (start, pause, unpause, stop) and Top Watched switches ranges', async ({
		page
	}) => {
		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);

		// Verify SSE connected + server name displayed in sidebar
		await expect(page.getByText('SSE connected')).toBeVisible();
		await expect(page.locator('aside').first()).toContainText('Miru Lab Plex');
		await expect(page.locator('aside').first()).toContainText('SSE');

		const kpiSection = page.getByRole('region', { name: 'Key telemetry metrics' });
		await expect(page.getByRole('heading', { name: 'Active Streams (0)' })).toBeVisible();
		await expect(page.getByText('No active streams right now')).toBeVisible();
		await expect(kpiSection).toContainText('0 playing · 0 paused');

		const baseSession: MockPlexSession = {
			sessionKey: 'live-sess-1',
			sessionId: 'live-id-1',
			ratingKey: '101',
			type: 'movie',
			title: 'Spirited Away',
			year: 2001,
			durationMs: 7_500_000,
			viewOffsetMs: 1_200_000,
			state: 'playing',
			user: { id: '1001', title: 'alice' },
			player: {
				machineIdentifier: 'appletv-living-room',
				product: 'Plex for Apple TV',
				platform: 'tvOS',
				device: 'Apple TV',
				title: 'Living Room Apple TV',
				address: '192.168.1.42',
				local: true
			},
			bandwidthKbps: 8_500,
			location: 'lan',
			decision: 'direct_play',
			videoResolution: '4k',
			videoCodec: 'hevc',
			audioCodec: 'eac3'
		};

		// 1. Stream start -> updates live over SSE without page reload
		mockPlex.setActiveSessions([baseSession]);
		mockPlex.pushPlayingNotification(baseSession.sessionKey, baseSession.ratingKey, 'playing', baseSession.viewOffsetMs);

		const streamCard = page.locator('article').filter({ hasText: 'Spirited Away' });
		await expect(page.getByRole('heading', { name: 'Active Streams (1)' })).toBeVisible();
		await expect(streamCard).toBeVisible();
		await expect(streamCard).toContainText('Playing');
		await expect(streamCard).toContainText('Direct Play');
		await expect(streamCard).toContainText('Alice Admin');
		await expect(kpiSection).toContainText('1 playing · 0 paused');
		await expect(kpiSection).toContainText('8.5 Mbps');

		// 2. Stream pause -> updates live over SSE
		const pausedSession: MockPlexSession = { ...baseSession, state: 'paused', viewOffsetMs: 1_500_000 };
		mockPlex.setActiveSessions([pausedSession]);
		mockPlex.pushPlayingNotification(pausedSession.sessionKey, pausedSession.ratingKey, 'paused', pausedSession.viewOffsetMs);

		await expect(streamCard).toContainText('Paused');
		await expect(kpiSection).toContainText('0 playing · 1 paused');

		// 3. Stream unpause (resume playing) -> updates live over SSE
		const resumedSession: MockPlexSession = { ...baseSession, state: 'playing', viewOffsetMs: 1_800_000 };
		mockPlex.setActiveSessions([resumedSession]);
		mockPlex.pushPlayingNotification(resumedSession.sessionKey, resumedSession.ratingKey, 'playing', resumedSession.viewOffsetMs);

		await expect(streamCard).toContainText('Playing');
		await expect(kpiSection).toContainText('1 playing · 0 paused');

		// 4. Stream stop -> removes card and returns KPIs to 0
		mockPlex.setActiveSessions([]);
		mockPlex.pushPlayingNotification(baseSession.sessionKey, baseSession.ratingKey, 'stopped', 2_100_000);

		// Well within the poll interval: the reported stop ends the stream without waiting for another missed poll
		await expect(page.getByRole('heading', { name: 'Active Streams (0)' })).toBeVisible({ timeout: 4_000 });
		await expect(page.getByText('No active streams right now')).toBeVisible();
		await expect(kpiSection).toContainText('0 playing · 0 paused');

		// 5. Top Watched range switching (7d -> 30d -> 90d -> all)
		const topWatchedCard = page.locator('section').filter({ hasText: 'Top Watched' }).locator('> div').last();

		await page.getByRole('tab', { name: '7d', exact: true }).click();
		await expect(topWatchedCard.getByRole('heading', { name: 'Top Watched (7d)' })).toBeVisible();
		await expect(topWatchedCard).toContainText('Spirited Away');
		await expect(topWatchedCard).not.toContainText('Akira');
		await expect(topWatchedCard).not.toContainText('Blade Runner 2049');

		await page.getByRole('tab', { name: '30d', exact: true }).click();
		await expect(topWatchedCard.getByRole('heading', { name: 'Top Watched (30d)' })).toBeVisible();
		await expect(topWatchedCard).toContainText('Akira');
		await expect(topWatchedCard).not.toContainText('Blade Runner 2049');

		await page.getByRole('tab', { name: '90d', exact: true }).click();
		await expect(topWatchedCard.getByRole('heading', { name: 'Top Watched (90d)' })).toBeVisible();
		await expect(topWatchedCard).toContainText('Blade Runner 2049');

		await page.getByRole('tab', { name: 'all', exact: true }).click();
		await expect(topWatchedCard.getByRole('heading', { name: 'Top Watched (all)' })).toBeVisible();
		await expect(topWatchedCard).toContainText('Severance');
		await expect(kpiSection).toContainText('Direct Play (all)');

		// 6. Viewing Heatmap & Clients/Codecs/Transcode analytics on Live page
		await expect(page.getByRole('heading', { name: 'Viewing Heatmap (7d)' })).toBeVisible();
		await expect(page.getByTestId('heatmap-day')).toHaveCount(7);
		const heatmapRangeGroup = page.getByRole('group', { name: 'Heatmap time range' });
		await heatmapRangeGroup.getByRole('button', { name: '30d' }).click();
		await expect(page.getByRole('heading', { name: 'Viewing Heatmap (30d)' })).toBeVisible();
		await expect(page.getByTestId('heatmap-day')).toHaveCount(30);
		await expect(page.getByRole('heading', { name: 'Top Players & Apps' })).toBeVisible();
		await expect(page.getByRole('heading', { name: 'Transcode & Remux Reasons' })).toBeVisible();
	});

	test('admins can terminate an active stream with custom reason while USER principals cannot see or invoke termination', async ({
		page,
		browser
	}) => {
		const adminCookie = await miru.apiLogin(E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		const users = await miru.apiGetUsers(adminCookie);
		const alice = users.find((u) => u.username === 'alice');
		if (!alice) {
			throw new Error(`Expected alice user in ${JSON.stringify(users)}`);
		}

		const viewerUsername = 'e2e_stream_viewer';
		const viewerPassword = 'StreamViewerPass123!';
		await miru.apiCreateAccount(adminCookie, {
			username: viewerUsername,
			password: viewerPassword,
			role: 'USER',
			linkedMediaUserIds: [alice.id]
		});

		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		await expect(page.getByText('SSE connected')).toBeVisible();

		mockPlex.clearTerminatedSessions();
		const activeSession: MockPlexSession = {
			sessionKey: 'term-sess-1',
			sessionId: 'term-plex-id-1',
			ratingKey: '101',
			type: 'movie',
			title: 'Spirited Away',
			year: 2001,
			durationMs: 7_500_000,
			viewOffsetMs: 1_200_000,
			state: 'playing',
			user: { id: '1001', title: 'alice' },
			player: {
				machineIdentifier: 'appletv-living-room',
				product: 'Plex for Apple TV',
				platform: 'tvOS',
				device: 'Apple TV',
				title: 'Living Room Apple TV',
				address: '192.168.1.42',
				local: true
			},
			bandwidthKbps: 18_500,
			location: 'lan',
			decision: 'transcode',
			hwTranscode: true,
			videoResolution: '4k',
			videoCodec: 'hevc',
			audioCodec: 'eac3'
		};

		mockPlex.setActiveSessions([activeSession]);
		mockPlex.pushPlayingNotification(activeSession.sessionKey, activeSession.ratingKey, 'playing', activeSession.viewOffsetMs);

		const adminStreamCard = page.locator('article').filter({ hasText: 'Spirited Away' });
		await expect(adminStreamCard).toBeVisible();
		await expect(adminStreamCard.getByRole('button', { name: 'Terminate' })).toBeVisible();

		// Verify USER principal sees the stream card without any Terminate button and gets 403 on direct POST
		const viewerContext = await browser.newContext();
		try {
			const viewerPage = await viewerContext.newPage();
			await loginViaUi(viewerPage, miru.baseUrl, viewerUsername, viewerPassword);
			const viewerCard = viewerPage.locator('article').filter({ hasText: 'Spirited Away' });
			await expect(viewerCard).toBeVisible();
			await expect(viewerCard.getByRole('button', { name: 'Terminate' })).toHaveCount(0);

			const forbiddenResp = await viewerPage.request.post(`${miru.baseUrl}/api/v1/streams/term-plex-id-1/terminate`, {
				data: { reason: 'Should fail' }
			});
			expect(forbiddenResp.status()).toBe(403);
		} finally {
			await viewerContext.close();
		}

		// Plex refuses while the server does not have the feature: the sheet says why and the stream stays
		mockPlex.setTerminationEnabled(false);
		await adminStreamCard.getByRole('button', { name: 'Terminate' }).click();
		await page.getByRole('dialog').getByRole('button', { name: 'Terminate Stream' }).click();
		await expect(page.getByRole('dialog').getByRole('alert')).toContainText('does not have this feature enabled');
		await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
		await expect(page.getByRole('dialog')).not.toBeVisible();
		mockPlex.setTerminationEnabled(true);

		// Admin opens sheet, fills preset, customizes reason, submits, and sees SSE remove the stream + audit entry in /admin
		await adminStreamCard.getByRole('button', { name: 'Terminate' }).click();
		const dialog = page.getByRole('dialog');
		await expect(dialog.getByText('Terminate Active Stream')).toBeVisible();
		await expect(dialog).toContainText('Alice Admin');
		await expect(dialog).toContainText('Spirited Away');

		await dialog.getByRole('button', { name: '4K Transcode' }).click();
		const reasonInput = dialog.getByPlaceholder(/Optional message shown/i);
		await expect(reasonInput).toHaveValue(/4K video transcoding/);
		await reasonInput.fill('Please switch to 1080p for now');
		await dialog.getByRole('button', { name: 'Terminate Stream' }).click();
		await expect(dialog).not.toBeVisible();

		await expect(page.getByRole('status')).toContainText('Termination requested for Alice Admin — Spirited Away');
		await expect(adminStreamCard.getByRole('button', { name: 'Terminating…' })).toBeDisabled();
		await expect(page.getByRole('heading', { name: 'Active Streams (0)' })).toBeVisible({ timeout: 5_000 });
		await expect(page.getByRole('status')).toHaveCount(0);
		expect(mockPlex.getTerminatedSessions()).toEqual([
			{ sessionId: 'term-plex-id-1', reason: 'Please switch to 1080p for now' }
		]);

		await page.getByRole('navigation', { name: 'Primary navigation' }).getByRole('link', { name: 'Admin' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/admin`);
		const guardrailsSection = page.locator('section').filter({ hasText: 'Stream Guardrails & Termination Audit' });
		await expect(guardrailsSection).toContainText('Please switch to 1080p for now');
		await expect(guardrailsSection).toContainText('FAILED');
		await expect(guardrailsSection).toContainText('Alice Admin');
	});
});
