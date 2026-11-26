/**
 * Copies text that is not known yet, such as a code an API call is about to return.
 *
 * Call it synchronously inside the click or submit handler, before awaiting anything:
 * browsers only allow clipboard writes during a user action. Safari in particular rejects a
 * write made after an `await`, but accepts a `ClipboardItem` whose data is a promise created
 * during the action. Other browsers fall back to `writeText` once the text arrives.
 *
 * Resolves to true when the text was copied, false when the browser refused or `pending` failed.
 */
export async function copyTextWhenReady(pending: Promise<string>): Promise<boolean> {
	const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
	if (!clipboard) {
		// Not a secure context (plain http on a LAN address) or no Clipboard API at all
		pending.catch(() => {});
		return false;
	}

	if (typeof ClipboardItem !== 'undefined' && typeof clipboard.write === 'function') {
		const blob = pending.then((text) => new Blob([text], { type: 'text/plain' }));
		blob.catch(() => {});
		try {
			await clipboard.write([new ClipboardItem({ 'text/plain': blob })]);
			return true;
		} catch {
			// Fall through to writeText, which Chromium and Firefox accept shortly after a user action
		}
	}

	try {
		await clipboard.writeText(await pending);
		return true;
	} catch {
		return false;
	}
}
