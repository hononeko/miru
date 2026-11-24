/**
 * Tracks which of several overlapping requests is the newest, so that a slow older response cannot replace a newer one.
 * Call the returned function when a request starts; what it returns tells whether that request is still the newest.
 */
export function latestRequest(): () => () => boolean {
	let newest = 0;
	return () => {
		const id = ++newest;
		return () => id === newest;
	};
}
