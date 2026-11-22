import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';
import type { ActiveStreamDto } from '$api/types';

export function cn(...inputs: ClassValue[]): string {
	return twMerge(clsx(inputs));
}

export function streamKey(stream: Pick<ActiveStreamDto, 'serverId' | 'sessionKey'> & { media: { ratingKey: string } }): string {
	return `${stream.serverId}:${stream.sessionKey}:${stream.media.ratingKey}`;
}

export function formatDurationSec(totalSeconds: number | null | undefined): string {
	if (!totalSeconds || totalSeconds <= 0) return '0m';
	const sec = Math.floor(totalSeconds);
	const hours = Math.floor(sec / 3600);
	const minutes = Math.floor((sec % 3600) / 60);
	const seconds = sec % 60;
	if (hours > 0) return `${hours}h ${minutes}m`;
	if (minutes > 0) return seconds > 0 ? `${minutes}m ${seconds}s` : `${minutes}m`;
	return `${seconds}s`;
}

export function formatClockMs(ms: number | null | undefined): string {
	if (!ms || ms <= 0) return '0:00';
	const totalSec = Math.floor(ms / 1000);
	const h = Math.floor(totalSec / 3600);
	const m = Math.floor((totalSec % 3600) / 60);
	const s = totalSec % 60;
	const pad = (n: number) => String(n).padStart(2, '0');
	return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
}

export function formatBytes(bytes: number | null | undefined): string {
	if (!bytes || bytes <= 0) return '0 B';
	const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
	let idx = 0;
	let value = bytes;
	while (value >= 1024 && idx < units.length - 1) {
		value /= 1024;
		idx++;
	}
	return `${value >= 100 ? value.toFixed(0) : value.toFixed(1)} ${units[idx]}`;
}

export function formatBandwidthKbps(kbps: number | null | undefined): string {
	if (!kbps || kbps <= 0) return '0 Mbps';
	if (kbps >= 1000) return `${(kbps / 1000).toFixed(1)} Mbps`;
	return `${Math.round(kbps)} kbps`;
}

export function formatRelativeTime(timestampMs: number | null | undefined, nowMs = Date.now()): string {
	if (!timestampMs) return 'Never';
	const diffSec = Math.max(0, Math.floor((nowMs - timestampMs) / 1000));
	if (diffSec < 60) return `${diffSec}s ago`;
	const diffMin = Math.floor(diffSec / 60);
	if (diffMin < 60) return `${diffMin}m ago`;
	const diffHr = Math.floor(diffMin / 60);
	if (diffHr < 24) return `${diffHr}h ago`;
	const diffDays = Math.floor(diffHr / 24);
	if (diffDays < 30) return `${diffDays}d ago`;
	return new Date(timestampMs).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}

export function formatShortDateTime(timestampMs: number | null | undefined): string {
	if (!timestampMs) return '—';
	const d = new Date(timestampMs);
	const now = new Date();
	const sameDay =
		d.getFullYear() === now.getFullYear() &&
		d.getMonth() === now.getMonth() &&
		d.getDate() === now.getDate();
	if (sameDay) {
		return `Today ${d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false })}`;
	}
	return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export function formatFullUtc(timestampMs: number | null | undefined): string {
	if (!timestampMs) return '—';
	return new Date(timestampMs).toISOString().replace('T', ' ').slice(0, 19) + ' UTC';
}

export interface DecisionBadgeMeta {
	label: string;
	shortLabel: string;
	tone: 'direct-play' | 'direct-stream' | 'hw-transcode' | 'sw-transcode';
}

export function getDecisionMeta(decision: string | null | undefined, hwTranscode = false): DecisionBadgeMeta {
	const norm = (decision ?? '').toLowerCase();
	if (norm === 'direct_play') {
		return { label: 'Direct Play', shortLabel: 'DIRECT PLAY', tone: 'direct-play' };
	}
	if (norm === 'direct_stream') {
		return { label: 'Direct Stream', shortLabel: 'REMUX', tone: 'direct-stream' };
	}
	if (hwTranscode) {
		return { label: 'HW Transcode', shortLabel: 'HW TRANSCODE', tone: 'hw-transcode' };
	}
	return { label: 'SW Transcode', shortLabel: 'SW TRANSCODE', tone: 'sw-transcode' };
}

export function formatMediaTitle(media: {
	mediaType: string;
	title: string;
	parentTitle?: string | null;
	grandparentTitle?: string | null;
	parentIndex?: number | null;
	mediaIndex?: number | null;
	year?: number | null;
}): { primary: string; secondary: string } {
	if (media.mediaType === 'episode' && media.grandparentTitle) {
		const s = media.parentIndex != null ? `S${String(media.parentIndex).padStart(2, '0')}` : '';
		const e = media.mediaIndex != null ? `E${String(media.mediaIndex).padStart(2, '0')}` : '';
		const code = [s, e].filter(Boolean).join(' · ');
		return {
			primary: media.grandparentTitle,
			secondary: code ? `${code} — ${media.title}` : media.title
		};
	}
	if (media.mediaType === 'track' && media.grandparentTitle) {
		return {
			primary: media.title,
			secondary: [media.grandparentTitle, media.parentTitle].filter(Boolean).join(' · ')
		};
	}
	const typeLabel = media.mediaType.replace('_', ' ');
	return {
		primary: media.title,
		secondary: [media.year, typeLabel.charAt(0).toUpperCase() + typeLabel.slice(1)].filter(Boolean).join(' · ')
	};
}

export function safeInitial(name: string | null | undefined): string {
	const trimmed = (name ?? '').trim();
	return trimmed ? trimmed[0].toUpperCase() : '?';
}
