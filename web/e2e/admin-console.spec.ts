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

test.describe('Admin console: Plex PIN OAuth, Tautulli import, local accounts & API keys', () => {
	let mockPlex: MockPlexServer;
	let miru: MiruServer;
	let fixture: TautulliFixture;

	test.beforeAll(async () => {
		mockPlex = await startMockPlexServer({
			serverName: 'Miru Lab Plex',
			autoAuthorizeAfterPolls: 0
		});
		// Zero-config Plex mode (no static PLEX_URL/PLEX_TOKEN) so PIN OAuth saves & hot-reloads
		miru = await startMiruServer({
			mockPlex,
			connectStaticPlex: false
		});
		fixture = createTautulliFixture();
	});

	test.afterAll(async () => {
		fixture?.cleanup();
		await miru?.stop();
		await mockPlex?.stop();
	});

	test('completes Plex PIN OAuth wizard, Tautulli dry-run & import, account CRUD/linking, and API key lifecycle', async ({
		page,
		context,
		request
	}) => {
		// Prevent popup window.open('https://app.plex.tv/auth...') from making external network requests
		await context.route('https://app.plex.tv/**', (route) =>
			route.fulfill({
				status: 200,
				contentType: 'text/html',
				body: '<html><body>Mock Plex Auth Tab</body></html>'
			})
		);
		context.on('page', (popup) => {
			if (popup.url().startsWith('https://app.plex.tv/')) {
				void popup.close().catch(() => {});
			}
		});

		await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);

		// Zero-config banner appears on / before Plex is connected
		await expect(page.getByText('Connect your Plex Media Server')).toBeVisible();
		await page.getByRole('link', { name: 'Connect in Admin' }).click();
		await expect(page).toHaveURL(`${miru.baseUrl}/admin`);
		await expect(page.getByRole('heading', { name: 'Admin & Access Control' })).toBeVisible();

		// --- 1. Plex PIN OAuth Wizard ---
		const plexSection = page.locator('section').filter({ hasText: 'Media Server · Plex Connection' });
		await expect(plexSection).toContainText('NOT CONFIGURED');
		await plexSection.getByRole('button', { name: 'Connect with Plex' }).click();

		const dialog = page.getByRole('dialog');
		await expect(dialog.getByText('Connect Plex Media Server')).toBeVisible();
		await expect(dialog.getByText('Waiting for Plex authorization…')).toBeVisible();
		await expect(dialog.getByRole('link', { name: 'Open app.plex.tv Sign-In' })).toHaveAttribute(
			'href',
			/https:\/\/app\.plex\.tv\/auth#\?/
		);

		// Authorize the PIN now that the waiting state is verified -> wizard advances on next poll
		mockPlex.authorizePin();
		await expect(dialog.getByText('1. Select Plex Server')).toBeVisible();
		await expect(dialog).toContainText('Miru Lab Plex');
		await expect(dialog.getByText('2. Choose Connection URI')).toBeVisible();
		await expect(dialog).toContainText(mockPlex.baseUrl);

		await dialog.getByRole('button', { name: 'Save & Connect Server' }).click();
		await expect(dialog.getByText('Miru Lab Plex Saved')).toBeVisible();
		await expect(dialog).toContainText('Miru hot-reloaded the live Plex pipeline without restarting.');
		await dialog.getByRole('button', { name: 'Done' }).click();
		await expect(dialog).not.toBeVisible();

		await expect(plexSection).toContainText('CONNECTED');
		await expect(plexSection).toContainText('STORED · ENCRYPTED');
		await expect(plexSection).toContainText('Miru Lab Plex');

		// --- 2. Tautulli Dry-Run & Import Flow ---
		const importSection = page.locator('section').filter({ hasText: 'Data Import (Tautulli)' });
		await importSection.locator('input[type="file"]').setInputFiles(fixture.filePath);
		await expect(importSection).toContainText('tautulli.db');

		// Dry Run Preview
		await importSection.getByRole('button', { name: 'Dry Run Preview' }).click();
		await expect(importSection).toContainText('DRY RUN PREVIEW');
		await expect(importSection.locator('div').filter({ hasText: /^Would Import\s*30$/ }).first()).toBeVisible();
		await expect(importSection.locator('div').filter({ hasText: /^Skipped Duplicates\s*0$/ }).first()).toBeVisible();

		// Real Import
		await importSection.getByRole('button', { name: 'Run Import' }).click();
		await expect(importSection).toContainText('IMPORT COMPLETE');
		await expect(importSection.locator('div').filter({ hasText: /^Imported\s*30$/ }).first()).toBeVisible();
		await expect(importSection.locator('div').filter({ hasText: /^Skipped Duplicates\s*0$/ }).first()).toBeVisible();

		// Re-run Import (idempotent: all 30 skipped as duplicates)
		await importSection.getByRole('button', { name: 'Run Import' }).click();
		await expect(importSection.locator('div').filter({ hasText: /^Imported\s*0$/ }).first()).toBeVisible();
		await expect(importSection.locator('div').filter({ hasText: /^Skipped Duplicates\s*30$/ }).first()).toBeVisible();

		// --- 3. Local Account Creation, Media User Linking & Deletion ---
		await page.getByRole('button', { name: 'Create Account' }).click();
		const createSheet = page.getByRole('dialog');
		await expect(createSheet.getByText('Create Local Account')).toBeVisible();

		// Password validation check inside Create Account sheet
		await createSheet.getByLabel('Username').fill('e2e_member');
		await createSheet.getByLabel('Email (optional)').fill('member@example.test');
		await createSheet.getByLabel('Password').fill('alllowercaseonly');
		await createSheet.getByRole('button', { name: 'Create Account' }).click();
		await expect(createSheet.getByRole('alert')).toContainText(/uppercase|digit|character/i);

		// Valid password creates the account
		await createSheet.getByLabel('Password').fill('MemberPass1234!');
		await createSheet.getByLabel('Role').selectOption('USER');
		await createSheet.getByRole('button', { name: 'Create Account' }).click();
		await expect(createSheet).not.toBeVisible();

		const accountsSection = page.locator('section').filter({ hasText: 'Accounts & Linked Media Users' });
		const memberRow = accountsSection.locator('> div > div').filter({ hasText: 'e2e_member' });
		await expect(memberRow).toBeVisible();
		await expect(memberRow).toContainText('No linked media users');

		// Link Alice Admin media user to e2e_member
		await memberRow.getByRole('button', { name: 'Link Users' }).click();
		const linkSheet = page.getByRole('dialog');
		await expect(linkSheet).toContainText('Link Media Users · e2e_member');
		await linkSheet.locator('label').filter({ hasText: 'Alice Admin' }).locator('input[type="checkbox"]').check();
		await linkSheet.getByRole('button', { name: 'Save Linked Users' }).click();
		await expect(linkSheet).not.toBeVisible();
		await expect(memberRow).toContainText('Alice Admin');

		// Delete e2e_member account
		await memberRow.getByRole('button', { name: 'Delete' }).click();
		await expect(accountsSection.locator('> div > div').filter({ hasText: 'e2e_member' })).toHaveCount(0);

		// --- 4. API Key Creation (with Expiration Interval), Live Verification & Revocation ---
		await page.getByRole('button', { name: 'New API Key' }).click();
		const keySheet = page.getByRole('dialog');
		await expect(keySheet.getByText('Create API Key')).toBeVisible();
		await keySheet.getByLabel('Key Name').fill('E2E Automation Key');
		await keySheet.getByLabel('Role').selectOption('ADMIN');
		await keySheet.getByLabel('Expiration').selectOption('30d');
		await keySheet.getByRole('button', { name: 'Generate API Key' }).click();
		await expect(keySheet).not.toBeVisible();

		const keysSection = page.locator('section').filter({ hasText: 'API Keys (X-Api-Key / Bearer)' });
		await expect(keysSection).toContainText('New API Key Created — Copy now, it will not be shown again');
		const rawKey = (await keysSection.locator('p.break-all.font-mono').innerText()).trim();
		expect(rawKey.length).toBeGreaterThan(16);

		// Verify API key authenticates against backend
		const authOk = await request.get(`${miru.baseUrl}/api/v1/users`, {
			headers: { 'X-Api-Key': rawKey }
		});
		expect(authOk.status()).toBe(200);

		// Verify expiration countdown and revoke API key
		const keyRow = keysSection.locator('> div > div').filter({ hasText: 'E2E Automation Key' });
		await expect(keyRow).toContainText('ACTIVE');
		await expect(keyRow).toContainText('(in 30d)');
		await keyRow.getByRole('button', { name: 'Revoke' }).click();
		await expect(keyRow).toContainText('REVOKED');

		// Revoked API key is immediately rejected
		const authRevoked = await request.get(`${miru.baseUrl}/api/v1/users`, {
			headers: { 'X-Api-Key': rawKey }
		});
		expect(authRevoked.status()).toBe(401);

		// --- 5. Stream Guardrails Configuration & Audit Log ---
		const guardrailsSection = page.locator('section').filter({ hasText: 'Stream Guardrails & Termination Audit' });
		await expect(guardrailsSection).toBeVisible();
		await expect(guardrailsSection).toContainText('4K Video Transcode');
		await expect(guardrailsSection).toContainText('Paused Stream Timeout');
		await expect(guardrailsSection).toContainText('Per-User Concurrent Stream Cap');
		await expect(guardrailsSection).toContainText('DISABLED');

		await guardrailsSection.getByRole('button', { name: 'Configure' }).first().click();
		const guardrailDialog = page.getByRole('dialog');
		await expect(guardrailDialog.getByText('Configure 4K Video Transcode')).toBeVisible();
		await guardrailDialog.getByLabel('Rule Mode').selectOption('WARN');
		await guardrailDialog.getByLabel(/Viewer Message/i).fill('Dry-run 4K notice');
		await guardrailDialog.getByRole('button', { name: 'Save Guardrail Rule' }).click();
		await expect(guardrailDialog).not.toBeVisible();

		await expect(guardrailsSection).toContainText('DRY-RUN WARN');
		await expect(guardrailsSection).toContainText('Dry-run 4K notice');

		await page.reload();
		await expect(guardrailsSection).toContainText('DRY-RUN WARN');
		await expect(guardrailsSection).toContainText('Dry-run 4K notice');
	});
});
