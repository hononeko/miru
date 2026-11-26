import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ActiveStreamDto, MediaDetailsResponse } from '$api/types';
import { mediaDetailsModal } from '$stores/mediaDetails.svelte';
import { serversStore } from '$stores/servers.svelte';
import MediaDetailsModal from './MediaDetailsModal.svelte';
import ProxiedImage from './ProxiedImage.svelte';
import ServerBadge from './ServerBadge.svelte';
import ServerFilterBar from './ServerFilterBar.svelte';
import StreamCard from './StreamCard.svelte';
import UnlinkedUserEmptyState from './UnlinkedUserEmptyState.svelte';

function makeTwoServers() {
	serversStore.servers = [
		{
			id: 'srv-plex',
			name: 'Home Plex',
			serverType: 'plex',
			configured: true,
			reachable: true,
			websocketEnabled: true,
			websocketState: 'CONNECTED'
		},
		{
			id: 'srv-jf',
			name: 'Studio Jellyfin',
			serverType: 'jellyfin',
			configured: true,
			reachable: false,
			websocketEnabled: false,
			websocketState: 'STOPPED'
		}
	];
	serversStore.loaded = true;
}

function makeJellyfinStream(): ActiveStreamDto {
	return {
		serverId: 'srv-jf',
		serverName: 'Studio Jellyfin',
		serverType: 'jellyfin',
		sessionKey: 'jf-s1',
		sessionId: 'jf-s1',
		state: 'playing',
		progressPercent: 25,
		viewOffsetMs: 300_000,
		startedAtMs: 1_700_000_000_000,
		lastSeenAtMs: 1_700_000_030_000,
		pausedDurationSec: 0,
		media: {
			ratingKey: 'jf-item-1',
			mediaType: 'movie',
			title: 'Spirited Away',
			year: 2001,
			thumbUrl: '/api/v1/media/jf-item-1/thumb',
			durationMs: 1_200_000
		},
		user: {
			id: 'u-jf',
			username: 'alice',
			displayName: 'Alice'
		},
		player: {
			name: 'Jellyfin Web',
			product: 'Jellyfin Web',
			platform: 'Firefox',
			location: 'lan'
		},
		technical: {
			streamDecision: 'direct_play',
			hwTranscode: false,
			throttled: false,
			bandwidthKbps: 12_000,
			videoResolution: '1080p',
			videoCodec: 'h264',
			audioCodec: 'aac'
		}
	};
}

describe('Multi-server UI components', () => {
	afterEach(() => {
		vi.restoreAllMocks();
		cleanup();
		serversStore.reset();
		mediaDetailsModal.close();
		mediaDetailsModal.data = null;
	});

	it('appends ?serverId= only for media proxy URLs in ProxiedImage', () => {
		const { container } = render(ProxiedImage, {
			props: {
				src: '/api/v1/media/jf-1/thumb',
				serverId: 'srv-jf',
				alt: 'Movie Poster',
				variant: 'poster'
			}
		});
		const poster = container.querySelector('img')!;
		expect(poster.getAttribute('src')).toBe('/api/v1/media/jf-1/thumb?serverId=srv-jf');
		cleanup();

		render(ProxiedImage, {
			props: {
				src: '/api/v1/users/u-jf/avatar',
				serverId: 'srv-jf',
				alt: 'Alice',
				variant: 'avatar'
			}
		});
		expect(screen.getByRole('img', { name: 'Alice' }).getAttribute('src')).toBe('/api/v1/users/u-jf/avatar');
	});

	it('renders ServerBadge and ServerFilterBar when multiple servers are configured and emits filter selection', async () => {
		makeTwoServers();
		const onchange = vi.fn();

		render(ServerBadge, {
			props: { serverId: 'srv-jf', serverType: 'jellyfin', serverName: 'Studio Jellyfin' }
		});
		expect(screen.getByText('JELLYFIN · Studio Jellyfin')).toBeTruthy();

		render(ServerFilterBar, {
			props: { selectedServerId: null, onchange }
		});
		const allBtn = screen.getByRole('button', { name: 'All Servers' });
		expect(allBtn.getAttribute('aria-pressed')).toBe('true');

		const jfBtn = screen.getByRole('button', { name: /Studio Jellyfin/ });
		await fireEvent.click(jfBtn);
		expect(onchange).toHaveBeenCalledWith('srv-jf');
	});

	it('shows compact server badge on StreamCard and passes serverId to mediaDetailsModal', async () => {
		makeTwoServers();
		const openSpy = vi.spyOn(mediaDetailsModal, 'open').mockResolvedValue(undefined);
		render(StreamCard, { props: { stream: makeJellyfinStream() } });

		expect(screen.getByText('JELLYFIN · Studio Jellyfin')).toBeTruthy();
		await fireEvent.click(screen.getByRole('button', { name: 'View media details for Spirited Away' }));
		expect(openSpy).toHaveBeenCalledWith('jf-item-1', { serverId: 'srv-jf' });
	});

	it('propagates serverId in MediaDetailsModal backdrop, Series link, and History link', () => {
		makeTwoServers();
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
		const details: MediaDetailsResponse = {
			ratingKey: 'jf-ep-1',
			serverId: 'srv-jf',
			serverName: 'Studio Jellyfin',
			serverType: 'jellyfin',
			mediaType: 'episode',
			title: 'Pilot',
			genres: ['Sci-Fi'],
			directors: [],
			thumbUrl: '/api/v1/media/jf-ep-1/thumb',
			artUrl: '/api/v1/media/jf-show-1/art',
			series: {
				ratingKey: 'jf-show-1',
				title: 'Andor',
				genres: ['Sci-Fi'],
				thumbUrl: '/api/v1/media/jf-show-1/thumb'
			},
			playSummary: {
				totalPlays: 1,
				uniqueUsers: 1,
				directPlay: 1,
				directStream: 0,
				transcode: 0,
				totalWatchDurationSec: 600
			}
		};
		mediaDetailsModal.isOpen = true;
		mediaDetailsModal.loading = false;
		mediaDetailsModal.error = null;
		mediaDetailsModal.data = details;

		const { container } = render(MediaDetailsModal);
		const backdrop = container.querySelector<HTMLImageElement>('img[aria-hidden="true"]');
		expect(backdrop?.getAttribute('src')).toBe('/api/v1/media/jf-show-1/art?serverId=srv-jf&w=960');
		expect(screen.getByText('JELLYFIN · Studio Jellyfin')).toBeTruthy();
		expect(screen.getByRole('link', { name: 'Filter in History' }).getAttribute('href')).toBe(
			'/history?q=Andor&serverId=srv-jf'
		);
	});

	it('uses server-agnostic copy in UnlinkedUserEmptyState', () => {
		render(UnlinkedUserEmptyState, { props: { context: 'playback history' } });
		expect(screen.getByRole('heading', { name: 'No media user linked to your account' })).toBeTruthy();
		expect(screen.queryByText(/Plex/i)).toBeNull();
	});
});
