import { expect, test } from '@playwright/test';
import { startMockJellyfinServer, type MockJellyfinServer } from './helpers/mock-jellyfin';
import {
	E2E_ADMIN_PASSWORD,
	E2E_ADMIN_USERNAME,
	loginViaUi,
	startMiruServer,
	type MiruServer
} from './helpers/miru-server';

test.describe('Admin console: Jellyfin Quick Connect onboarding', () => {
	let mockJellyfin: MockJellyfinServer;
	let miru: MiruServer;

	test.beforeAll(async () => {
		mockJellyfin = await startMockJellyfinServer({
			serverName: 'Studio Jellyfin',
			machineId: 'e2e-jf-qc-machine-001'
		});
		// Zero-config Miru (no JELLYFIN_URL or JELLYFIN_API_KEY env vars)
		miru = await startMiruServer();
	});

	test.afterAll(async () => {
		await miru?.stop();
		await mockJellyfin?.stop();
	});

	test('connects Jellyfin via Quick Connect from /admin with zero Jellyfin env vars, rejects non-admin first, and hot-reloads live telemetry', async ({
		page
	}) => {
		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);

		await expect(page.getByText('Connect your Media Server')).toBeVisible();
		await page.getByRole('link', { name: 'Connect in Admin' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/admin`);

		const serversSection = page.locator('section').filter({ hasText: 'Media Servers & Readiness' });
		const jellyfinRow = serversSection.locator('> div > div').filter({ hasText: 'JELLYFIN' });
		await expect(jellyfinRow).toContainText('NOT CONFIGURED');
		await expect(jellyfinRow).not.toContainText('JELLYFIN_URL');

		// 1. Non-admin approval is rejected cleanly
		await jellyfinRow.getByRole('button', { name: 'Connect Jellyfin' }).click();
		const dialog = page.getByRole('dialog');
		await expect(dialog.getByText('Connect Jellyfin Server')).toBeVisible();
		await dialog.getByLabel('Jellyfin Server URL').fill(mockJellyfin.baseUrl);
		await dialog.getByRole('button', { name: 'Connect', exact: true }).click();

		await expect(dialog.getByTestId('jellyfin-qc-code')).toBeVisible();
		await expect(dialog).toContainText('Studio Jellyfin');
		await expect(dialog).toContainText('v10.10.3');

		mockJellyfin.approveQuickConnect({ isAdministrator: false, userName: 'diana' });
		await expect(dialog.getByText('Jellyfin Authorization Failed')).toBeVisible();
		await expect(dialog.getByRole('alert')).toContainText('administrator');
		expect(mockJellyfin.getLogoutCount()).toBe(1);
		expect(mockJellyfin.getIssuedApiKeys()).toHaveLength(0);

		// 2. Retry and approve as administrator
		await dialog.getByRole('button', { name: 'Try Again' }).click();
		await dialog.getByRole('button', { name: 'Connect', exact: true }).click();
		await expect(dialog.getByTestId('jellyfin-qc-code')).toBeVisible();

		mockJellyfin.approveQuickConnect({ isAdministrator: true, userName: 'alice' });
		await expect(dialog.getByText('Studio Jellyfin Connected')).toBeVisible();
		await expect(dialog).toContainText('Miru hot-reloaded the live Jellyfin pipeline without restarting.');

		await dialog.getByRole('button', { name: 'Done' }).click();
		await expect(dialog).not.toBeVisible();

		await expect(jellyfinRow).toContainText('CONNECTED');
		await expect(jellyfinRow).toContainText('STORED · ENCRYPTED');
		await expect(jellyfinRow).toContainText('Studio Jellyfin');
		await expect(jellyfinRow.getByRole('button', { name: 'Reconnect Jellyfin' })).toBeVisible();

		expect(mockJellyfin.getLogoutCount()).toBe(2);
		expect(mockJellyfin.getIssuedApiKeys()).toHaveLength(1);

		// Verify live Jellyfin WebSocket & library sync activated without restart
		await mockJellyfin.waitForWsClient(15_000);
		const cookie = await miru.apiLogin(E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		const libraries = await miru.waitForLibraries(cookie, 2);
		expect(libraries.map((l) => l.title)).toContain('Jellyfin Movies');

		// 3. Reconnect rotates key and revokes previous key
		await jellyfinRow.getByRole('button', { name: 'Reconnect Jellyfin' }).click();
		await expect(dialog.getByLabel('Jellyfin Server URL')).toHaveValue(mockJellyfin.baseUrl);
		await dialog.getByRole('button', { name: 'Connect', exact: true }).click();
		await expect(dialog.getByTestId('jellyfin-qc-code')).toBeVisible();
		mockJellyfin.approveQuickConnect({ isAdministrator: true, userName: 'alice' });
		await expect(dialog.getByText('Studio Jellyfin Connected')).toBeVisible();
		await dialog.getByRole('button', { name: 'Done' }).click();

		const issuedKeys = mockJellyfin.getIssuedApiKeys();
		const revokedKeys = mockJellyfin.getRevokedApiKeys();
		expect(issuedKeys).toHaveLength(2);
		expect(revokedKeys).toEqual([issuedKeys[0]]);

		// Verify no issued secrets leaked in server logs
		const allLogs = miru.logs.join('\n');
		for (const key of issuedKeys) {
			expect(allLogs).not.toContain(key);
		}
	});
});
