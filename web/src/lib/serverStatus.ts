import type { MediaServerSummaryDto } from '$api/types';

export type ServerStatusKind = 'live' | 'polling' | 'reconnecting' | 'offline' | 'unknown';

export type ServerStatus = {
	kind: ServerStatusKind;
	/** Short label shown next to the server name */
	label: string;
	/** Longer explanation for tooltips and screen readers */
	description: string;
	/** Tailwind class for the status dot */
	dotClass: string;
	/** Tailwind class for the status label text */
	textClass: string;
};

const STATUSES: Record<ServerStatusKind, ServerStatus> = {
	live: {
		kind: 'live',
		label: 'Live',
		description: 'Reachable, with real-time updates over WebSocket',
		dotClass: 'bg-direct-play',
		textClass: 'text-direct-play'
	},
	polling: {
		kind: 'polling',
		label: 'Polling',
		description: 'Reachable; Miru checks for changes on a timer because the WebSocket is off or not connected',
		dotClass: 'bg-direct-stream',
		textClass: 'text-direct-stream'
	},
	reconnecting: {
		kind: 'reconnecting',
		label: 'Reconnecting',
		description: 'Reachable; the WebSocket is reconnecting and Miru polls in the meantime',
		dotClass: 'bg-warning',
		textClass: 'text-warning'
	},
	offline: {
		kind: 'offline',
		label: 'Offline',
		description: 'Miru cannot reach this server',
		dotClass: 'bg-danger',
		textClass: 'text-danger'
	},
	unknown: {
		kind: 'unknown',
		label: 'Unknown',
		description: 'Miru could not refresh server status, so it may be out of date',
		dotClass: 'bg-fg-muted',
		textClass: 'text-fg-muted'
	}
};

/**
 * Maps a media server summary to the status shown in the shell.
 *
 * `stale` is true when the last refresh of `/api/v1/servers` failed, so the summary
 * may no longer describe the server.
 */
export function getServerStatus(
	server: Pick<MediaServerSummaryDto, 'reachable' | 'websocketEnabled' | 'websocketState'>,
	options: { stale?: boolean } = {}
): ServerStatus {
	if (options.stale) return STATUSES.unknown;
	if (!server.reachable) return STATUSES.offline;
	if (server.websocketEnabled) {
		const state = server.websocketState.trim().toUpperCase();
		if (state === 'CONNECTED') return STATUSES.live;
		if (state === 'CONNECTING' || state === 'RECONNECTING' || state === 'BACKOFF') {
			return STATUSES.reconnecting;
		}
	}
	return STATUSES.polling;
}
