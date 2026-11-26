import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it } from 'vitest';
import ProxiedImage from './ProxiedImage.svelte';

describe('ProxiedImage', () => {
	afterEach(() => {
		cleanup();
	});
	it('renders avatar proxy URL and reveals image on load while showing initial until ready', async () => {
		const { container } = render(ProxiedImage, {
			props: {
				src: '/api/v1/users/u-1/avatar',
				alt: 'Alex Kerr',
				fallbackLabel: 'Alex Kerr',
				variant: 'avatar',
				class: 'h-10 w-10'
			}
		});

		const img = screen.getByRole('img', { name: 'Alex Kerr' }) as HTMLImageElement;
		expect(img.getAttribute('src')).toBe('/api/v1/users/u-1/avatar');
		expect(img.className).toContain('opacity-0');
		expect(container.textContent).toContain('A');

		await fireEvent.load(img);
		expect(img.className).toContain('opacity-100');
		expect(container.querySelector('span[aria-hidden="true"]')).toBeNull();
	});

	it('falls back cleanly to initial when avatar image fails to load', async () => {
		const { container } = render(ProxiedImage, {
			props: {
				src: '/api/v1/users/u-missing/avatar',
				alt: 'Yuki Tanaka',
				fallbackLabel: 'Yuki Tanaka',
				variant: 'avatar'
			}
		});

		const img = screen.getByRole('img', { name: 'Yuki Tanaka' });
		await fireEvent.error(img);

		expect(screen.queryByRole('img')).toBeNull();
		expect(container.textContent?.trim()).toBe('Y');
	});

	it('blocks non-proxy external URLs and renders initial fallback', () => {
		const { container } = render(ProxiedImage, {
			props: {
				src: 'https://plex.tv/users/raw/avatar?X-Plex-Token=secret',
				alt: 'Blocked User',
				fallbackLabel: 'Blocked User',
				variant: 'avatar'
			}
		});

		expect(screen.queryByRole('img')).toBeNull();
		expect(container.textContent?.trim()).toBe('B');
	});

	it('renders initial fallback when src is null', () => {
		const { container } = render(ProxiedImage, {
			props: {
				src: null,
				alt: 'Guest',
				fallbackLabel: 'Guest',
				variant: 'avatar'
			}
		});

		expect(screen.queryByRole('img')).toBeNull();
		expect(container.textContent?.trim()).toBe('G');
	});

	it('renders poster fallback when poster image fails or is missing', async () => {
		const { container } = render(ProxiedImage, {
			props: {
				src: '/api/v1/media/42/thumb',
				alt: 'Spirited Away',
				fallbackLabel: 'Spirited Away',
				variant: 'poster'
			}
		});

		const img = screen.getByRole('img', { name: 'Spirited Away' });
		await fireEvent.error(img);

		expect(screen.queryByRole('img')).toBeNull();
		expect(container.textContent).toContain('Spirited Away');
	});

	it('resets failed/loaded state when serverId changes on the same src', async () => {
		const { rerender } = render(ProxiedImage, {
			props: {
				src: '/api/v1/media/42/thumb',
				serverId: 'srv-1',
				alt: 'Spirited Away',
				fallbackLabel: 'Spirited Away',
				variant: 'avatar'
			}
		});

		const firstImg = screen.getByRole('img', { name: 'Spirited Away' }) as HTMLImageElement;
		expect(firstImg.getAttribute('src')).toBe('/api/v1/media/42/thumb?serverId=srv-1');
		await fireEvent.error(firstImg);
		expect(screen.queryByRole('img')).toBeNull();

		await rerender({
			src: '/api/v1/media/42/thumb',
			serverId: 'srv-2',
			alt: 'Spirited Away',
			fallbackLabel: 'Spirited Away',
			variant: 'avatar'
		});

		const retryImg = screen.getByRole('img', { name: 'Spirited Away' }) as HTMLImageElement;
		expect(retryImg.getAttribute('src')).toBe('/api/v1/media/42/thumb?serverId=srv-2');
		expect(retryImg.className).toContain('opacity-0');
		await fireEvent.load(retryImg);
		expect(retryImg.className).toContain('opacity-100');

		await rerender({
			src: '/api/v1/media/42/thumb',
			serverId: 'srv-3',
			alt: 'Spirited Away',
			fallbackLabel: 'Spirited Away',
			variant: 'avatar'
		});

		const thirdImg = screen.getByRole('img', { name: 'Spirited Away' }) as HTMLImageElement;
		expect(thirdImg.getAttribute('src')).toBe('/api/v1/media/42/thumb?serverId=srv-3');
		expect(thirdImg.className).toContain('opacity-0');
	});
});
