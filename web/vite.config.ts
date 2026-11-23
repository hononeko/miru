import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vitest/config';

const apiTarget = process.env.MIRU_API_URL || 'http://localhost:8080';

export default defineConfig({
	plugins: [
		tailwindcss(),
		sveltekit({
			preprocess: vitePreprocess(),
			adapter: adapter({
				pages: 'build',
				assets: 'build',
				fallback: 'index.html',
				precompress: false,
				strict: true
			}),
			alias: {
				$lib: 'src/lib',
				$components: 'src/lib/components',
				$api: 'src/lib/api',
				$stores: 'src/lib/stores'
			}
		})
	],
	resolve: process.env.VITEST ? { conditions: ['browser'] } : undefined,
	server: {
		port: 5173,
		proxy: {
			'/api': {
				target: apiTarget,
				changeOrigin: true
			},
			'/healthz': {
				target: apiTarget,
				changeOrigin: true
			},
			'/readyz': {
				target: apiTarget,
				changeOrigin: true
			}
		}
	},
	test: {
		environment: 'happy-dom',
		include: ['src/**/*.{test,spec}.{js,ts}']
	}
});
