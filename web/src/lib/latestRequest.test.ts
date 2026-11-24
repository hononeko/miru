import { describe, expect, it } from 'vitest';
import { latestRequest } from './latestRequest';

describe('latestRequest', () => {
	it('only recognises the newest request, separately for each tracker', () => {
		const start = latestRequest();
		const other = latestRequest();

		const first = start();
		expect(first()).toBe(true);

		const second = start();
		other();
		expect(first()).toBe(false);
		expect(second()).toBe(true);
	});
});
