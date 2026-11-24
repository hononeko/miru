import { expect, test, type Locator, type Page } from '@playwright/test';
import { startMockPlexServer, type MockPlexServer } from './helpers/mock-plex';
import {
	E2E_ADMIN_PASSWORD,
	E2E_ADMIN_USERNAME,
	startMiruServer,
	type MiruServer
} from './helpers/miru-server';
import { createTautulliFixture, type TautulliFixture } from './helpers/tautulli-fixture';

test.use({
	// Measure settled layout rather than entrance animation frames.
	contextOptions: { reducedMotion: 'reduce' },
	// Headless Chromium hides scrollbars, which would hide the page shifting when one disappears.
	launchOptions: { ignoreDefaultArgs: ['--hide-scrollbars'] }
});

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

const openPlayDetails = MODALS[2].open;

/** Opens media details from the play details sheet, which makes way for it. */
async function openMediaDetailsFromSheet(page: Page): Promise<Locator> {
	const sheet = await openPlayDetails(page);
	await sheet.getByRole('button', { name: 'Media info →' }).click();
	const dialog = page.locator('dialog.media-details-dialog');
	await expect(dialog.getByRole('link', { name: 'Filter in History' })).toBeVisible();
	await expect(page.locator('[role="dialog"]')).toHaveCount(0);
	return dialog;
}

/** Swipes with one finger. Playwright has no touch drag, and CDP's synthesized scroll gesture sends wheel events. */
async function swipe(page: Page, from: { x: number; y: number }, distanceY: number) {
	const cdp = await page.context().newCDPSession(page);
	await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [from] });
	const steps = 20;
	for (let step = 1; step <= steps; step++) {
		const point = { x: from.x, y: from.y + (distanceY * step) / steps };
		await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [point] });
		await page.waitForTimeout(10);
	}
	await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
	await cdp.detach();
	await page.waitForTimeout(400);
}

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
	let cookie: string;

	/** One session for every test: logging in for each would run into the login rate limit. */
	async function openHistory(page: Page) {
		const [name, value] = cookie.split(/=(.*)/s);
		await page.context().addCookies([{ name, value, url: miru.baseUrl }]);
		await page.goto(`${miru.baseUrl}/history`);
		await expect(page.getByText('30 PLAYS')).toBeVisible();
	}

	test.beforeAll(async () => {
		mockPlex = await startMockPlexServer();
		miru = await startMiruServer({ mockPlex });
		fixture = createTautulliFixture();

		cookie = await miru.apiLogin(E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
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
					await openHistory(page);

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
					test.skip(device.hasTouch && browserName !== 'chromium', 'Touch input is sent over CDP');
					await openHistory(page);

					const widthBefore = await page.evaluate(() => document.querySelector('main')!.getBoundingClientRect().width);
					const dialog = await modal.open(page);
					const point = await backdropPoint(page, dialog);
					const before = await scrollY(page);

					// Chromium contains the scroll on its own, so also check the lock other browsers rely on.
					await expect(page.locator('body')).toHaveCSS('overflow-y', 'hidden');

					if (device.hasTouch) {
						const box = (await dialog.boundingBox())!;
						await swipe(page, { x: point.x, y: point.y }, -Math.min(60, point.y - 4));
						await swipe(page, { x: box.x + box.width / 2, y: box.y + box.height * 0.8 }, -box.height * 0.6);
						await swipe(page, { x: box.x + box.width / 2, y: box.y + box.height * 0.8 }, -box.height * 0.6);
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

			test('media details opened from play details: every way to close it works', async ({ page }) => {
				await openHistory(page);
				const mainWidth = () => page.evaluate(() => document.querySelector('main')!.getBoundingClientRect().width);
				const widthBefore = await mainWidth();
				const press = (point: { x: number; y: number }) =>
					device.hasTouch ? page.touchscreen.tap(point.x, point.y) : page.mouse.click(point.x, point.y);

				const closings: Array<(dialog: Locator) => Promise<void>> = [
					async (dialog) => {
						const box = (await dialog.getByRole('button', { name: 'Close', exact: true }).boundingBox())!;
						await press({ x: box.x + box.width / 2, y: box.y + box.height / 2 });
					},
					async (dialog) => press(await backdropPoint(page, dialog)),
					async () => page.keyboard.press('Escape')
				];
				for (const close of closings) {
					const dialog = await openMediaDetailsFromSheet(page);
					// Past the sheet's own cleanup, the page is still locked and has not shifted
					await page.waitForTimeout(300);
					await expect(page.locator('body')).toHaveCSS('overflow-y', 'hidden');
					expect(await mainWidth()).toBe(widthBefore);

					// Pressing the content keeps it open
					const box = (await dialog.boundingBox())!;
					await press({ x: box.x + box.width / 2, y: box.y + box.height / 2 });
					await page.waitForTimeout(200);
					await expect(dialog).toBeVisible();

					await close(dialog);
					await expect(dialog).not.toBeVisible();
					await page.waitForTimeout(300);
					await expect(page.locator('dialog[open], [role="dialog"]')).toHaveCount(0);
					await expect(page.locator('body')).not.toHaveCSS('overflow-y', 'hidden');
					await expect(page.locator('body')).toHaveCSS('pointer-events', 'auto');
					expect(await mainWidth()).toBe(widthBefore);
				}
			});

			test('media details opened from play details: filtering in history shows the filtered list', async ({ page }) => {
				await openHistory(page);

				const dialog = await openMediaDetailsFromSheet(page);
				await dialog.getByRole('link', { name: 'Filter in History' }).click();
				await expect(page).toHaveURL(/\/history\?q=/);
				await expect(dialog).not.toBeVisible();
				await expect(page.getByLabel('Search playback history')).not.toHaveValue('');
			});

			test('a dialog stays usable while the body ignores pointer events, as it does under a bits-ui dialog', async ({ page }) => {
				await openHistory(page);
				const dialog = await MODALS[0].open(page);
				await page.evaluate(() => (document.body.style.pointerEvents = 'none'));
				const close = (await dialog.getByRole('button', { name: 'Close', exact: true }).boundingBox())!;
				const point = { x: close.x + close.width / 2, y: close.y + close.height / 2 };
				if (device.hasTouch) {
					await page.touchscreen.tap(point.x, point.y);
				} else {
					await page.mouse.click(point.x, point.y);
				}
				await expect(dialog).not.toBeVisible();
			});

			test('swiping down on the backdrop does not move or close the dialog', async ({ page, browserName }) => {
				test.skip(!device.hasTouch || browserName !== 'chromium', 'Touch input is sent over CDP');
				await openHistory(page);

				for (const modal of MODALS.slice(0, 2)) {
					const dialog = await modal.open(page);
					const box = (await dialog.boundingBox())!;
					const point = await backdropPoint(page, dialog);
					await swipe(page, { x: point.x, y: Math.min(20, point.y) }, 500);
					await expect(dialog).toBeVisible();
					expect(await dialog.boundingBox()).toEqual(box);
					await page.keyboard.press('Escape');
					await expect(dialog).not.toBeVisible();
				}
			});
		});
	}
});
