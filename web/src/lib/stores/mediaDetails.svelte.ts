import { api, MiruApiError, unwrap } from '$api/client';
import type { MediaDetailsResponse } from '$api/types';

export type MediaDetailsFetcher = (ratingKey: string, serverId?: string | null) => Promise<MediaDetailsResponse>;

export interface MediaDetailsOpenOptions {
	preferSeries?: boolean;
	serverId?: string | null;
}

async function defaultFetchMediaDetails(
	ratingKey: string,
	serverId?: string | null
): Promise<MediaDetailsResponse> {
	const normalizedServerId = serverId?.trim() || undefined;
	return unwrap(
		await api.GET('/api/v1/media/{ratingKey}', {
			params: {
				path: { ratingKey },
				...(normalizedServerId ? { query: { serverId: normalizedServerId } } : {})
			}
		})
	);
}

export class MediaDetailsModalStore {
	isOpen = $state(false);
	ratingKey = $state<string | null>(null);
	serverId = $state<string | null>(null);
	loading = $state(false);
	error = $state<string | null>(null);
	data = $state<MediaDetailsResponse | null>(null);

	private requestToken = 0;
	private readonly fetcher: MediaDetailsFetcher;

	constructor(fetcher: MediaDetailsFetcher = defaultFetchMediaDetails) {
		this.fetcher = fetcher;
	}

	async open(ratingKey: string, options?: MediaDetailsOpenOptions): Promise<void> {
		const trimmed = ratingKey.trim();
		if (!trimmed) return;

		const requestedServerId = options?.serverId?.trim() || null;
		const token = ++this.requestToken;
		this.isOpen = true;
		this.ratingKey = trimmed;
		this.serverId = requestedServerId;
		this.loading = true;
		this.error = null;
		this.data = null;

		try {
			const response = await this.fetcher(trimmed, requestedServerId);
			if (token !== this.requestToken) return;
			const resolvedServerId = response.serverId?.trim() || requestedServerId;
			const seriesKey = response.series?.ratingKey?.trim();
			if (
				options?.preferSeries &&
				response.mediaType !== 'show' &&
				seriesKey &&
				seriesKey !== response.ratingKey
			) {
				try {
					const seriesResponse = await this.fetcher(seriesKey, resolvedServerId);
					if (token !== this.requestToken) return;
					this.ratingKey = seriesResponse.ratingKey;
					this.serverId = seriesResponse.serverId?.trim() || resolvedServerId;
					this.data = seriesResponse;
				} catch {
					if (token !== this.requestToken) return;
					this.serverId = resolvedServerId;
					this.data = response;
				}
			} else {
				this.serverId = resolvedServerId;
				this.data = response;
			}
		} catch (err) {
			if (token !== this.requestToken) return;
			if (err instanceof MiruApiError) {
				this.error = err.detail;
			} else if (err instanceof Error && err.message.trim().length > 0) {
				this.error = err.message;
			} else {
				this.error = 'Failed to load media details';
			}
		} finally {
			if (token === this.requestToken) {
				this.loading = false;
			}
		}
	}

	close(): void {
		this.requestToken++;
		this.isOpen = false;
		this.loading = false;
		this.error = null;
	}
}

export const mediaDetailsModal = new MediaDetailsModalStore();
