import { lockPageScroll } from '$lib/scrollLock';

/** Whether a touch on `target` scrolls something inside the dialog, or drags a form control. */
function scrollsContent(target: EventTarget | null, dialog: HTMLDialogElement): boolean {
	for (let el = target instanceof Element ? target : null; el && el !== dialog.parentElement; el = el.parentElement) {
		if (el.matches('input, select, textarea')) return true;
		const style = getComputedStyle(el);
		if (el.scrollHeight > el.clientHeight && /auto|scroll/.test(style.overflowY)) return true;
		if (el.scrollWidth > el.clientWidth && /auto|scroll/.test(style.overflowX)) return true;
	}
	return false;
}

/**
 * Makes a native modal `<dialog>` block the page behind it: the page cannot scroll while it is open, and pressing the
 * backdrop closes it without activating whatever is underneath.
 *
 * The backdrop closes on `click`, not on `pointerup` or with `closedby="any"`. A touch dispatches its mouse events and
 * click after `pointerup`, so a dialog that is already gone by then lets them through to the page. The listeners are
 * native ones because iOS Safari only sends clicks to elements that have their own listener, and Svelte delegates
 * `onclick` to the app root.
 */
export function modalDialog(dialog: HTMLDialogElement) {
	let pressedBackdrop = false;
	let touchScrollsContent = false;
	let unlockScroll: (() => void) | null = null;

	function isBackdrop(e: MouseEvent): boolean {
		if (e.target !== dialog) return false;
		const r = dialog.getBoundingClientRect();
		return e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom;
	}

	function onPointerDown(e: PointerEvent) {
		pressedBackdrop = isBackdrop(e);
	}

	function onClick(e: MouseEvent) {
		// A drag that starts inside the dialog and ends on the backdrop, e.g. selecting text, must not close it.
		const dismiss = pressedBackdrop && isBackdrop(e);
		pressedBackdrop = false;
		if (dismiss) dialog.close();
	}

	function onTouchStart(e: TouchEvent) {
		touchScrollsContent = scrollsContent(e.target, dialog);
	}

	// iOS Safari scrolls the page with a touch that has nothing to scroll inside the dialog: one on the backdrop, the
	// header or the footer. `overscroll-behavior: contain` only covers content that overflows.
	function onTouchMove(e: TouchEvent) {
		const pinching = e.touches.length > 1;
		if (!touchScrollsContent && !pinching && e.cancelable) e.preventDefault();
	}

	function syncScrollLock() {
		if (dialog.open) {
			unlockScroll ??= lockPageScroll();
		} else {
			unlockScroll?.();
			unlockScroll = null;
		}
	}

	const openObserver = new MutationObserver(syncScrollLock);
	openObserver.observe(dialog, { attributes: true, attributeFilter: ['open'] });
	syncScrollLock();

	dialog.addEventListener('pointerdown', onPointerDown);
	dialog.addEventListener('click', onClick);
	dialog.addEventListener('touchstart', onTouchStart, { passive: true });
	dialog.addEventListener('touchmove', onTouchMove, { passive: false });

	return {
		destroy() {
			openObserver.disconnect();
			unlockScroll?.();
			unlockScroll = null;
			dialog.removeEventListener('pointerdown', onPointerDown);
			dialog.removeEventListener('click', onClick);
			dialog.removeEventListener('touchstart', onTouchStart);
			dialog.removeEventListener('touchmove', onTouchMove);
		}
	};
}
