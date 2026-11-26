import { describe, expect, it } from 'vitest';
import { getServerStatus, type ServerStatusKind } from './serverStatus';

describe('getServerStatus', () => {
	const cases: Array<{
		name: string;
		reachable: boolean;
		websocketEnabled: boolean;
		websocketState: string;
		expected: ServerStatusKind;
	}> = [
		{ name: 'reachable with a connected WebSocket', reachable: true, websocketEnabled: true, websocketState: 'CONNECTED', expected: 'live' },
		{ name: 'reachable with the WebSocket turned off', reachable: true, websocketEnabled: false, websocketState: 'DISCONNECTED', expected: 'polling' },
		{ name: 'reachable with an enabled but stopped WebSocket', reachable: true, websocketEnabled: true, websocketState: 'STOPPED', expected: 'polling' },
		{ name: 'reachable with an enabled but disconnected WebSocket', reachable: true, websocketEnabled: true, websocketState: 'DISCONNECTED', expected: 'polling' },
		{ name: 'reachable while the WebSocket connects', reachable: true, websocketEnabled: true, websocketState: 'CONNECTING', expected: 'reconnecting' },
		{ name: 'reachable while the WebSocket backs off', reachable: true, websocketEnabled: true, websocketState: 'BACKOFF', expected: 'reconnecting' },
		{ name: 'states in lower case', reachable: true, websocketEnabled: true, websocketState: 'connected', expected: 'live' },
		{ name: 'unreachable even if the WebSocket reports connected', reachable: false, websocketEnabled: true, websocketState: 'CONNECTED', expected: 'offline' },
		{ name: 'unreachable while the WebSocket backs off', reachable: false, websocketEnabled: true, websocketState: 'BACKOFF', expected: 'offline' }
	];

	for (const { name, expected, ...server } of cases) {
		it(`is ${expected} when ${name}`, () => {
			expect(getServerStatus(server).kind).toBe(expected);
		});
	}

	it('is unknown when the last status refresh failed, whatever the summary says', () => {
		const status = getServerStatus(
			{ reachable: true, websocketEnabled: true, websocketState: 'CONNECTED' },
			{ stale: true }
		);
		expect(status.kind).toBe('unknown');
		expect(status.label).toBe('Unknown');
	});

	it('gives every status a label, description and colours', () => {
		const servers = [
			{ reachable: true, websocketEnabled: true, websocketState: 'CONNECTED' },
			{ reachable: true, websocketEnabled: false, websocketState: 'DISCONNECTED' },
			{ reachable: true, websocketEnabled: true, websocketState: 'CONNECTING' },
			{ reachable: false, websocketEnabled: true, websocketState: 'BACKOFF' }
		];
		const labels = servers.map((server) => getServerStatus(server).label);
		expect(labels).toEqual(['Live', 'Polling', 'Reconnecting', 'Offline']);
		for (const server of servers) {
			const status = getServerStatus(server);
			expect(status.description.length).toBeGreaterThan(0);
			expect(status.dotClass).toMatch(/^bg-/);
			expect(status.textClass).toMatch(/^text-/);
		}
	});
});
