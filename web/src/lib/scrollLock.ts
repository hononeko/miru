let activeLocks = 0;

/**
 * Stops the page from scrolling until the returned function is called. Locks nest: the page scrolls again once every
 * lock is released. Uses an attribute (styled in app.css) rather than inline styles because bits-ui saves and restores
 * the body's inline overflow for its own dialogs.
 */
export function lockPageScroll(): () => void {
	if (activeLocks === 0) {
		// Hiding a classic scrollbar widens the page, so pad the body by the same amount. A bits-ui dialog that is
		// closing may have hidden it already; it leaves the width on the body until it takes its own padding off.
		const hiddenByBitsUi = Number.parseFloat(document.body.style.getPropertyValue('--scrollbar-width')) || 0;
		const gutter = Math.max(hiddenByBitsUi, window.innerWidth - document.documentElement.clientWidth, 0);
		document.body.style.setProperty('--scroll-lock-gutter', `${gutter}px`);
		document.body.setAttribute('data-scroll-locked', '');
	}
	activeLocks++;

	let released = false;
	return () => {
		if (released) return;
		released = true;
		activeLocks--;
		if (activeLocks === 0) {
			document.body.removeAttribute('data-scroll-locked');
			document.body.style.removeProperty('--scroll-lock-gutter');
		}
	};
}
