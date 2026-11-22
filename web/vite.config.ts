import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vitest/config';

const apiTarget = process.env.MIRU_API_URL || 'http://localhost:8080';

export default defineConfig({
	plugins: [tailwindcss(), sveltekit()],
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
