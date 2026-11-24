import { expect, test, type Locator, type Page } from '@playwright/test';
import { startMockPlexServer, type MockPlexServer } from './helpers/mock-plex';
import {
	E2E_ADMIN_PASSWORD,
	E2E_ADMIN_USERNAME,
	loginViaUi,
	startMiruServer,
	type MiruServer
} from './helpers/miru-server';
import { createTautulliFixture, type TautulliFixture } from './helpers/tautulli-fixture';

// Measure settled layout rather than entrance animation frames.
test.use({ contextOptions: { reducedMotion: 'reduce' } });

type ModalCase = {
	name: string;
	open: (page: Page) => Promise<Locator>;
};

const MODALS: ModalCase[] = [
	{
		name: 'media details dialog',
		open: async (page) => {
			await page
				.getByRole('button', { name: 'Spirited Away' })
				.filter({ visible: true })
				.first()
				.click();
			const dialog = page.locator('dialog.media-details-dialog');
			await expect(dialog.getByRole('heading', { name: 'Spirited Away' })).toBeVisible();
			return dialog;
		}
	},
	{
		name: 'user preferences dialog',
		open: async (page) => {
			await page
				.getByRole('button', { name: /user preferences/i })
				.filter({ visible: true })
				.first()
				.click();
			const dialog = page.locator('dialog[aria-labelledby="user-preferences-title"]');
			await expect(dialog).toBeVisible();
			return dialog;
		}
	},
	{
		name: 'play details sheet',
		open: async (page) => {
			const mobileDetails = page.getByRole('button', { name: 'Details', exact: true }).first();
			if (await mobileDetails.isVisible()) {
				await mobileDetails.click();
			} else {
				await page.locator('table tbody tr').first().locator('td').first().click();
			}
			const dialog = page.getByRole('dialog').filter({ hasText: 'Play Details' });
			await expect(dialog.getByText('Transcode').or(dialog.getByText('Direct')).first()).toBeVisible();
			return dialog;
		}
	}
];

/** Counts pointer presses and clicks that land on the page behind the modal. */
async function recordBackgroundClicks(page: Page) {
	await page.evaluate(() => {
		const seen: string[] = [];
		(window as unknown as { __backgroundClicks: string[] }).__backgroundClicks = seen;
		for (const type of ['mousedown', 'click'] as const) {
			document.addEventListener(
				type,
				(e) => {
					const target = e.target as Element;
					const main = target.closest('main, nav, aside, header');
					if (main && !target.closest('dialog, [role="dialog"]')) {
						seen.push(`${type} <${target.tagName.toLowerCase()}> ${target.textContent?.trim().slice(0, 40)}`);
					}
				},
				true
			);
		}
	});
}

function backgroundClicks(page: Page): Promise<string[]> {
	return page.evaluate(() => (window as unknown as { __backgroundClicks: string[] }).__backgroundClicks);
}

/** A point on the backdrop, next to or above the modal. */
async function backdropPoint(page: Page, dialog: Locator): Promise<{ x: number; y: number }> {
	const viewport = page.viewportSize()!;
	const box = (await dialog.boundingBox())!;
	const point =
		box.x > 80
			? { x: box.x / 2, y: viewport.height / 2 }
			: { x: viewport.width / 2, y: box.y / 2 };
	expect(point.y).toBeGreaterThan(8);
	return point;
}

async function scrollY(page: Page): Promise<number> {
	return page.evaluate(() => window.scrollY);
}

const DEVICES = [
	{ name: 'desktop mouse', viewport: { width: 1280, height: 640 }, hasTouch: false, isMobile: false },
	{ name: 'tablet touch', viewport: { width: 820, height: 1000 }, hasTouch: true, isMobile: true },
	{ name: 'phone touch', viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true }
];

test.describe('Modals block the page behind them', () => {
	let mockPlex: MockPlexServer;
	let miru: MiruServer;
	let fixture: TautulliFixture;

	test.beforeAll(async () => {
		mockPlex = await startMockPlexServer();
		miru = await startMiruServer({ mockPlex });
		fixture = createTautulliFixture();

		const cookie = await miru.apiLogin(E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		await miru.waitForUsers(cookie, 2);
		await miru.waitForLibraries(cookie, 2);
		await miru.apiTriggerLibrarySync(cookie, true);
		await miru.apiImportTautulli(cookie, fixture.filePath, false);
	});

	test.afterAll(async () => {
		fixture?.cleanup();
		await miru?.stop();
		await mockPlex?.stop();
	});

	for (const device of DEVICES) {
		test.describe(device.name, () => {
			test.use({ viewport: device.viewport, hasTouch: device.hasTouch, isMobile: device.isMobile });

			for (const modal of MODALS) {
				test(`${modal.name}: pressing the backdrop closes it without activating the page behind`, async ({ page }) => {
					await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
					await page.goto(`${miru.baseUrl}/history`);
					await expect(page.getByText('30 PLAYS')).toBeVisible();

					const dialog = await modal.open(page);
					const point = await backdropPoint(page, dialog);
					await recordBackgroundClicks(page);
					if (device.hasTouch) {
						await page.touchscreen.tap(point.x, point.y);
					} else {
						await page.mouse.click(point.x, point.y);
					}

					await expect(dialog).not.toBeVisible();
					await page.waitForTimeout(400);
					expect(await backgroundClicks(page)).toEqual([]);
					await expect(page).toHaveURL(`${miru.baseUrl}/history`);
					await expect(page.locator('dialog[open], [role="dialog"]')).toHaveCount(0);
				});

				test(`${modal.name}: the page behind does not scroll`, async ({ page, browserName }) => {
					test.skip(device.hasTouch && browserName !== 'chromium', 'Touch scrolling is synthesized over CDP');
					await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
					await page.goto(`${miru.baseUrl}/history`);
					await expect(page.getByText('30 PLAYS')).toBeVisible();

					const widthBefore = await page.evaluate(() => document.querySelector('main')!.getBoundingClientRect().width);
					const dialog = await modal.open(page);
					const point = await backdropPoint(page, dialog);
					const before = await scrollY(page);

					// Chromium contains the scroll on its own, so also check the lock other browsers rely on.
					await expect(page.locator('body')).toHaveCSS('overflow-y', 'hidden');

					if (device.hasTouch) {
						const cdp = await page.context().newCDPSession(page);
						await cdp.send('Input.synthesizeScrollGesture', { ...point, yDistance: -400, speed: 2000 });
					} else {
						await page.mouse.move(point.x, point.y);
						await page.mouse.wheel(0, 600);
						const box = (await dialog.boundingBox())!;
						await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
						await page.mouse.wheel(0, 5000);
						await page.mouse.wheel(0, 5000);
					}
					await page.waitForTimeout(400);
					expect(await scrollY(page)).toBe(before);
					await expect(dialog).toBeVisible();

					const widthDuring = await page.evaluate(() => document.querySelector('main')!.getBoundingClientRect().width);
					expect(widthDuring).toBe(widthBefore);

					// Scrolling comes back once the modal closes
					await page.keyboard.press('Escape');
					await expect(dialog).not.toBeVisible();
					await page.waitForTimeout(100);
					await page.mouse.move(10, 300);
					await page.mouse.wheel(0, 300);
					await expect.poll(() => scrollY(page)).toBeGreaterThan(before);
				});
			}
		});
	}
});
