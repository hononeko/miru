import { defineConfig, devices } from '@playwright/test';

const isCI = Boolean(process.env.CI);

export default defineConfig({
	testDir: './e2e',
	fullyParallel: false,
	forbidOnly: isCI,
	retries: isCI ? 1 : 0,
	workers: isCI ? 2 : 2,
	timeout: 60_000,
	expect: {
		timeout: 15_000
	},
	reporter: [
		['list'],
		['html', { open: 'never', outputFolder: 'playwright-report' }]
	],
	outputDir: 'test-results',
	use: {
		trace: 'retain-on-failure',
		screenshot: 'only-on-failure',
		video: 'retain-on-failure',
		actionTimeout: 15_000,
		navigationTimeout: 20_000
	},
	projects: [
		{
			name: 'chromium',
			use: { ...devices['Desktop Chrome'] }
		}
	]
});
