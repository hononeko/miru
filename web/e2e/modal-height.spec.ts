import { expect, test, type Locator, type Page } from '@playwright/test';
import { startMockPlexServer, type MockPlexServer } from './helpers/mock-plex';
import {
	E2E_ADMIN_PASSWORD,
	E2E_ADMIN_USERNAME,
	loginViaUi,
	startMiruServer,
	type MiruServer
} from './helpers/miru-server';

const VIEWPORTS = [
	{ name: 'phone', width: 390, height: 844 },
	{ name: 'iPad portrait', width: 820, height: 1180 },
	{ name: 'iPad landscape', width: 1180, height: 820 },
	{ name: 'desktop', width: 1440, height: 900 }
];

/** A modal must either show all of its content or grow to its max-height before scrolling. */
async function expectModalUsesAvailableHeight(dialog: Locator) {
	await expect
		.poll(() =>
			dialog.evaluate((el) => {
				const body = el.querySelector<HTMLElement>('[data-modal-body]');
				if (!body) return 'missing scroll body';
				const hidden = body.scrollHeight - body.clientHeight;
				const unused = parseFloat(getComputedStyle(el).maxHeight) - el.getBoundingClientRect().height;
				return hidden <= 1 || unused <= 1 ? 'ok' : `hides ${hidden}px of content with ${unused}px of height unused`;
			})
		)
		.toBe('ok');
}

/**
 * WebKit before 26.4 resolves a 0% flex-basis (Tailwind `flex-1`) to 0px inside an auto-height dialog, collapsing the
 * modal. Current browsers lay it out correctly, so check the cause instead of relying on the engine running the test.
 */
async function expectAutoFlexBasis(dialog: Locator) {
	const bases = await dialog.evaluate((el) => {
		const found: string[] = [];
		for (let node = el.querySelector('[data-modal-body]'); node && node !== el; node = node.parentElement) {
			found.push(getComputedStyle(node).flexBasis);
		}
		return found;
	});
	expect(bases.length).toBeGreaterThan(0);
	expect(bases.filter((basis) => basis !== 'auto')).toEqual([]);
}

async function expectInsideViewport(page: Page, dialog: Locator) {
	const footerButton = dialog.getByRole('button', { name: /^(Close|Save Preferences)$/ });
	await expect(footerButton).toBeInViewport({ ratio: 1 });
	const box = await dialog.boundingBox();
	const viewport = page.viewportSize();
	expect(box).not.toBeNull();
	expect(box!.y).toBeGreaterThanOrEqual(0);
	expect(box!.y + box!.height).toBeLessThanOrEqual(viewport!.height + 1);
}

// Measure settled layout rather than entrance animation frames.
test.use({ contextOptions: { reducedMotion: 'reduce' } });

test.describe('Modal height across phone, tablet and desktop viewports', () => {
	let mockPlex: MockPlexServer;
	let miru: MiruServer;

	test.beforeAll(async () => {
		mockPlex = await startMockPlexServer();
		miru = await startMiruServer({ mockPlex });

		const adminCookie = await miru.apiLogin(E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
		await miru.waitForLibraries(adminCookie, 2);
		await miru.apiTriggerLibrarySync(adminCookie, true);
	});

	test.afterAll(async () => {
		await miru?.stop();
		await mockPlex?.stop();
	});

	for (const viewport of VIEWPORTS) {
		test.describe(viewport.name, () => {
			test.use({ viewport: { width: viewport.width, height: viewport.height } });

			test('user preferences modal shows its content', async ({ page }) => {
				await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
				await page
					.getByRole('button', { name: /user preferences/i })
					.filter({ visible: true })
					.first()
					.click();

				const dialog = page.locator('dialog[aria-labelledby="user-preferences-title"]');
				await expect(dialog).toBeVisible();
				await expectAutoFlexBasis(dialog);
				await expectModalUsesAvailableHeight(dialog);
				await expectInsideViewport(page, dialog);
			});

			test('media details modal shows its content', async ({ page }) => {
				await loginViaUi(page, miru.baseUrl, E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD);
				await page.goto(`${miru.baseUrl}/libraries/2`);
				await page.getByRole('button', { name: /Good News About Hell/ }).click();

				const dialog = page.locator('dialog.media-details-dialog');
				await expect(dialog.getByRole('heading', { name: 'Good News About Hell' })).toBeVisible();
				await expectAutoFlexBasis(dialog);
				await expectModalUsesAvailableHeight(dialog);
				await expectInsideViewport(page, dialog);
			});
		});
	}
});
