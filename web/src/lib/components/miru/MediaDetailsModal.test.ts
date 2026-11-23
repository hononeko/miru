import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MediaDetailsResponse } from '$api/types';
import { mediaDetailsModal } from '$stores/mediaDetails.svelte';
import MediaDetailsModal from './MediaDetailsModal.svelte';

function makeDetails(overrides: Partial<MediaDetailsResponse> = {}): MediaDetailsResponse {
	return {
		ratingKey: '54321',
		mediaType: 'episode',
		title: 'Ozymandias',
		genres: ['Drama'],
		directors: [],
		thumbUrl: '/api/v1/media/54321/thumb',
		artUrl: '/api/v1/media/54000/art',
		season: {
			ratingKey: '54300',
			title: 'Season 5',
			thumbUrl: '/api/v1/media/54300/thumb'
		},
		series: {
			ratingKey: '54000',
			title: 'Breaking Bad',
			genres: ['Drama'],
			thumbUrl: '/api/v1/media/54000/thumb'
		},
		playSummary: {
			totalPlays: 2,
			uniqueUsers: 1,
			avgCompletionPercent: 0.4,
			directPlayCount: 2,
			directStreamCount: 0,
			transcodeCount: 0,
			totalWatchDurationSec: 30
		},
		...overrides
	};
}

describe('MediaDetailsModal', () => {
	beforeEach(() => {
		if (!HTMLDialogElement.prototype.showModal) {
			HTMLDialogElement.prototype.showModal = function showModal() {
				this.setAttribute('open', '');
			};
		}
		if (!HTMLDialogElement.prototype.close) {
			HTMLDialogElement.prototype.close = function close() {
				this.removeAttribute('open');
			};
		}
		mediaDetailsModal.close();
		mediaDetailsModal.data = null;
	});

	afterEach(() => {
		mediaDetailsModal.close();
		mediaDetailsModal.data = null;
		cleanup();
	});

	it('offers a Close button while loading so desktop users can dismiss slow requests', async () => {
		mediaDetailsModal.isOpen = true;
		mediaDetailsModal.loading = true;
		mediaDetailsModal.error = null;
		mediaDetailsModal.data = null;

		const closeSpy = vi.spyOn(mediaDetailsModal, 'close');
		render(MediaDetailsModal);

		const closeBtn = screen.getByRole('button', { name: 'Close' });
		expect(closeBtn).toBeTruthy();
		await fireEvent.click(closeBtn);
		expect(closeSpy).toHaveBeenCalledTimes(1);
		closeSpy.mockRestore();
	});

	it('falls back through art -> item thumb -> season thumb -> series thumb and formats sub-1% average completion as "<1%"', async () => {
		mediaDetailsModal.isOpen = true;
		mediaDetailsModal.loading = false;
		mediaDetailsModal.error = null;
		mediaDetailsModal.data = makeDetails();

		const { container } = render(MediaDetailsModal);

		expect(screen.getByText('<1% AVG COMPLETION')).toBeTruthy();

		const backdrop = () => container.querySelector<HTMLImageElement>('img[aria-hidden="true"]');
		expect(backdrop()?.getAttribute('src')).toBe('/api/v1/media/54000/art?w=960');

		await fireEvent.error(backdrop()!);
		expect(backdrop()?.getAttribute('src')).toBe('/api/v1/media/54321/thumb?w=960');

		await fireEvent.error(backdrop()!);
		expect(backdrop()?.getAttribute('src')).toBe('/api/v1/media/54300/thumb?w=960');

		await fireEvent.error(backdrop()!);
		expect(backdrop()?.getAttribute('src')).toBe('/api/v1/media/54000/thumb?w=960');
	});
});
