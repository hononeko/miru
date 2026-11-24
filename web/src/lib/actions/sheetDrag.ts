export interface SheetDragOptions {
	onDismiss: () => void;
	maxViewportWidthPx?: number;
	dismissDistancePx?: number;
	dismissVelocityPxPerMs?: number;
	minFlickDistancePx?: number;
}

const DEFAULT_MAX_VIEWPORT_WIDTH_PX = 767;
const DEFAULT_DISMISS_DISTANCE_PX = 100;
const DEFAULT_DISMISS_VELOCITY_PX_PER_MS = 0.45;
const DEFAULT_MIN_FLICK_DISTANCE_PX = 36;
const SNAP_BACK_MS = 200;

export function shouldDismissSheetDrag(
	offsetY: number,
	velocityPxPerMs: number,
	sheetHeight: number,
	options: Pick<
		SheetDragOptions,
		'dismissDistancePx' | 'dismissVelocityPxPerMs' | 'minFlickDistancePx'
	> = {}
): boolean {
	if (offsetY <= 0) return false;
	const maxDistance = options.dismissDistancePx ?? DEFAULT_DISMISS_DISTANCE_PX;
	const proportional = Math.max(60, sheetHeight * 0.25);
	const threshold = Math.min(maxDistance, proportional);
	if (offsetY >= threshold) return true;

	const minFlickDist = options.minFlickDistancePx ?? DEFAULT_MIN_FLICK_DISTANCE_PX;
	const minVelocity = options.dismissVelocityPxPerMs ?? DEFAULT_DISMISS_VELOCITY_PX_PER_MS;
	return offsetY >= minFlickDist && velocityPxPerMs >= minVelocity;
}

function isMobileViewport(maxWidthPx: number): boolean {
	if (typeof window === 'undefined') return false;
	if (typeof window.innerWidth === 'number' && window.innerWidth > 0) {
		return window.innerWidth <= maxWidthPx;
	}
	return Boolean(window.matchMedia?.(`(max-width: ${maxWidthPx}px)`).matches);
}

function isInteractiveControl(target: EventTarget | null): boolean {
	return target instanceof Element && Boolean(target.closest('input[type="range"], select, textarea'));
}

function areScrollContainersAtTop(target: EventTarget | null, boundary: HTMLElement): boolean {
	let el: Element | null = target instanceof Element ? target : null;
	while (el) {
		if (el.scrollTop > 0) return false;
		if (el === boundary) break;
		el = el.parentElement;
	}
	return boundary.scrollTop <= 0;
}

/** A native dialog is itself the target of touches on its ::backdrop, which lies outside its box. */
function isOnBackdrop(touch: Touch, target: EventTarget | null, node: HTMLElement): boolean {
	if (!(node instanceof HTMLDialogElement) || target !== node) return false;
	const r = node.getBoundingClientRect();
	return touch.clientX < r.left || touch.clientX > r.right || touch.clientY < r.top || touch.clientY > r.bottom;
}

export function sheetDrag(node: HTMLElement, initialOptions: SheetDragOptions) {
	let currentOptions = initialOptions;
	let tracking = false;
	let active = false;
	let startOnHandle = false;
	let activePointerId: number | null = null;
	let startX = 0;
	let startY = 0;
	let startTime = 0;
	let lastY = 0;
	let lastTime = 0;
	let offsetY = 0;
	let velocity = 0;
	let snapBackTimer: ReturnType<typeof setTimeout> | null = null;

	function clearSnapBackTimer() {
		if (snapBackTimer !== null) {
			clearTimeout(snapBackTimer);
			snapBackTimer = null;
		}
	}

	function clearInlineStyles() {
		clearSnapBackTimer();
		node.style.transition = '';
		node.style.transform = '';
		node.style.willChange = '';
	}

	function resetGestureState() {
		tracking = false;
		active = false;
		startOnHandle = false;
		activePointerId = null;
		offsetY = 0;
		velocity = 0;
	}

	function beginTracking(x: number, y: number, target: EventTarget | null, viaPointerId: number | null) {
		clearSnapBackTimer();
		const now = performance.now();
		tracking = true;
		active = false;
		startOnHandle = target instanceof Element && Boolean(target.closest('[data-sheet-drag-handle]'));
		activePointerId = viaPointerId;
		startX = x;
		startY = y;
		startTime = now;
		lastY = y;
		lastTime = now;
		offsetY = 0;
		velocity = 0;
	}

	function updateMovement(x: number, y: number, target: EventTarget | null, preventDefault: () => void): boolean {
		if (!tracking) return false;

		const dx = x - startX;
		const dy = y - startY;

		if (!active) {
			if (dy < -4 || (!startOnHandle && Math.abs(dx) > dy && Math.abs(dx) > 4)) {
				resetGestureState();
				return false;
			}
			const minDelta = startOnHandle ? 4 : 8;
			const directionOk = startOnHandle ? dy >= Math.abs(dx) : dy > Math.abs(dx);
			if (dy < minDelta || !directionOk) {
				return false;
			}
			if (!startOnHandle && !areScrollContainersAtTop(target, node)) {
				resetGestureState();
				return false;
			}
			active = true;
			node.style.transition = 'none';
			node.style.willChange = 'transform';
		}

		preventDefault();
		const nextOffset = Math.max(0, dy);
		const now = performance.now();
		const dt = now - lastTime;
		if (dt > 0) {
			velocity = Math.max(0, (y - lastY) / dt);
		} else if (now > startTime) {
			velocity = Math.max(0, nextOffset / (now - startTime));
		}
		lastY = y;
		lastTime = now;
		offsetY = nextOffset;
		node.style.transform = `translateY(${offsetY}px)`;
		return true;
	}

	function snapBack() {
		node.style.transition = `transform ${SNAP_BACK_MS}ms cubic-bezier(0.22, 1, 0.36, 1)`;
		node.style.transform = 'translateY(0px)';
		clearSnapBackTimer();
		snapBackTimer = setTimeout(() => {
			clearInlineStyles();
		}, SNAP_BACK_MS + 40);
	}

	function finishGesture(cancelled: boolean) {
		if (!tracking) return;
		const wasActive = active;
		const finalOffset = offsetY;
		const finalVelocity = velocity;
		resetGestureState();

		if (!wasActive) return;

		const height = node.getBoundingClientRect().height || node.offsetHeight || 400;
		if (!cancelled && shouldDismissSheetDrag(finalOffset, finalVelocity, height, currentOptions)) {
			clearInlineStyles();
			currentOptions.onDismiss();
		} else {
			snapBack();
		}
	}

	function onTouchStart(e: TouchEvent) {
		if (e.touches.length !== 1) {
			if (active) {
				finishGesture(true);
			} else {
				resetGestureState();
			}
			return;
		}
		const maxWidth = currentOptions.maxViewportWidthPx ?? DEFAULT_MAX_VIEWPORT_WIDTH_PX;
		if (!isMobileViewport(maxWidth)) return;

		const touch = e.touches[0];
		if (isOnBackdrop(touch, e.target, node)) return;

		const onHandle = e.target instanceof Element && Boolean(e.target.closest('[data-sheet-drag-handle]'));
		if (!onHandle) {
			if (isInteractiveControl(e.target)) return;
			if (!areScrollContainersAtTop(e.target, node)) return;
		}

		beginTracking(touch.clientX, touch.clientY, e.target, null);
	}

	function onTouchMove(e: TouchEvent) {
		if (!tracking || activePointerId !== null) return;
		if (e.touches.length !== 1) {
			finishGesture(true);
			return;
		}
		const touch = e.touches[0];
		updateMovement(touch.clientX, touch.clientY, e.target, () => {
			if (e.cancelable) e.preventDefault();
		});
	}

	function onTouchEnd() {
		if (!tracking || activePointerId !== null) return;
		finishGesture(false);
	}

	function onTouchCancel() {
		if (!tracking || activePointerId !== null) return;
		finishGesture(true);
	}

	function onPointerDown(e: PointerEvent) {
		if (e.pointerType === 'touch' || e.button !== 0) return;
		const maxWidth = currentOptions.maxViewportWidthPx ?? DEFAULT_MAX_VIEWPORT_WIDTH_PX;
		if (!isMobileViewport(maxWidth)) return;
		if (!(e.target instanceof Element) || !e.target.closest('[data-sheet-drag-handle]')) return;
		beginTracking(e.clientX, e.clientY, e.target, e.pointerId);
	}

	function onPointerMove(e: PointerEvent) {
		if (!tracking || activePointerId !== e.pointerId) return;
		updateMovement(e.clientX, e.clientY, e.target, () => {
			if (e.cancelable) e.preventDefault();
		});
	}

	function onPointerUp(e: PointerEvent) {
		if (!tracking || activePointerId !== e.pointerId) return;
		finishGesture(false);
	}

	function onPointerCancel(e: PointerEvent) {
		if (!tracking || activePointerId !== e.pointerId) return;
		finishGesture(true);
	}

	function onTransitionEnd(e: TransitionEvent) {
		if (e.target === node && e.propertyName === 'transform' && !active) {
			clearInlineStyles();
		}
	}

	node.addEventListener('touchstart', onTouchStart, { passive: true });
	node.addEventListener('touchmove', onTouchMove, { passive: false });
	node.addEventListener('touchend', onTouchEnd, { passive: true });
	node.addEventListener('touchcancel', onTouchCancel, { passive: true });
	node.addEventListener('pointerdown', onPointerDown);
	node.addEventListener('transitionend', onTransitionEnd);
	window.addEventListener('pointermove', onPointerMove, { passive: false });
	window.addEventListener('pointerup', onPointerUp);
	window.addEventListener('pointercancel', onPointerCancel);

	return {
		update(nextOptions: SheetDragOptions) {
			currentOptions = nextOptions;
		},
		destroy() {
			clearInlineStyles();
			resetGestureState();
			node.removeEventListener('touchstart', onTouchStart);
			node.removeEventListener('touchmove', onTouchMove);
			node.removeEventListener('touchend', onTouchEnd);
			node.removeEventListener('touchcancel', onTouchCancel);
			node.removeEventListener('pointerdown', onPointerDown);
			node.removeEventListener('transitionend', onTransitionEnd);
			window.removeEventListener('pointermove', onPointerMove);
			window.removeEventListener('pointerup', onPointerUp);
			window.removeEventListener('pointercancel', onPointerCancel);
		}
	};
}
