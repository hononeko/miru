/**
 * Link to the Quick Connect page of Jellyfin's web app, with the code filled in.
 *
 * Jellyfin's web app (checked against 10.11.11) serves it at `/web/#/quickconnect` and reads
 * the code from a `code` query parameter. Versions that ignore the parameter still open the
 * right page, where the code can be pasted.
 *
 * Returns null unless `baseUrl` is an http(s) URL.
 */
export function jellyfinQuickConnectUrl(baseUrl: string, code: string): string | null {
	let parsed: URL;
	try {
		parsed = new URL(baseUrl.trim());
	} catch {
		return null;
	}
	if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;

	const base = `${parsed.origin}${parsed.pathname.replace(/\/+$/, '')}`;
	return `${base}/web/#/quickconnect?code=${encodeURIComponent(code.trim())}`;
}
