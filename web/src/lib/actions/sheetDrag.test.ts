import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { sheetDrag, shouldDismissSheetDrag } from './sheetDrag';

function createSheetDom(scrollTop = 0) {
	const sheet = document.createElement('div');
	Object.defineProperty(sheet, 'offsetHeight', { configurable: true, value: 480 });
	sheet.getBoundingClientRect = () =>
		({
			top: 100,
			left: 0,
			right: 390,
			bottom: 580,
			width: 390,
			height: 480,
			x: 0,
			y: 100,
			toJSON: () => ({})
		}) as DOMRect;

	const handle = document.createElement('div');
	handle.setAttribute('data-sheet-drag-handle', '');
	const body = document.createElement('div');
	Object.defineProperty(body, 'scrollTop', { configurable: true, writable: true, value: scrollTop });

	sheet.appendChild(handle);
	sheet.appendChild(body);
	document.body.appendChild(sheet);
	return { sheet, handle, body };
}

function dispatchTouch(target: EventTarget, type: string, clientX: number, clientY: number) {
	const touches = type === 'touchend' || type === 'touchcancel' ? [] : [{ clientX, clientY }];
	const event = new Event(type, { bubbles: true, cancelable: true }) as unknown as TouchEvent;
	Object.defineProperty(event, 'touches', { value: touches });
	Object.defineProperty(event, 'changedTouches', { value: [{ clientX, clientY }] });
	target.dispatchEvent(event);
}

function dispatchPointer(
	target: EventTarget,
	type: string,
	clientX: number,
	clientY: number,
	pointerType = 'mouse',
	pointerId = 1
) {
	const event = new Event(type, { bubbles: true, cancelable: true }) as unknown as PointerEvent;
	Object.defineProperty(event, 'clientX', { value: clientX });
	Object.defineProperty(event, 'clientY', { value: clientY });
	Object.defineProperty(event, 'pointerType', { value: pointerType });
	Object.defineProperty(event, 'pointerId', { value: pointerId });
	Object.defineProperty(event, 'button', { value: 0 });
	target.dispatchEvent(event);
}

describe('shouldDismissSheetDrag', () => {
	it('evaluates distance, proportional height, and flick velocity thresholds', () => {
		expect(shouldDismissSheetDrag(0, 1.5, 480)).toBe(false);
		expect(shouldDismissSheetDrag(-20, 1.5, 480)).toBe(false);
		expect(shouldDismissSheetDrag(110, 0.1, 480)).toBe(true);
		expect(shouldDismissSheetDrag(70, 0.05, 240)).toBe(true);
		expect(shouldDismissSheetDrag(45, 0.6, 480)).toBe(true);
		expect(shouldDismissSheetDrag(20, 0.8, 480)).toBe(false);
		expect(shouldDismissSheetDrag(45, 0.2, 480)).toBe(false);
	});
});

describe('sheetDrag action', () => {
	const originalWidth = window.innerWidth;

	beforeEach(() => {
		vi.useFakeTimers();
		Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 390 });
	});

	afterEach(() => {
		vi.useRealTimers();
		Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: originalWidth });
		document.body.innerHTML = '';
	});

	it('dismisses when dragging down on the handle past threshold and clears inline transform', () => {
		const onDismiss = vi.fn();
		const { sheet, handle } = createSheetDom();
		const action = sheetDrag(sheet, { onDismiss });

		dispatchTouch(handle, 'touchstart', 150, 120);
		dispatchTouch(handle, 'touchmove', 150, 140);
		expect(sheet.style.transform).toBe('translateY(20px)');

		vi.advanceTimersByTime(100);
		dispatchTouch(handle, 'touchmove', 150, 250);
		expect(sheet.style.transform).toBe('translateY(130px)');

		dispatchTouch(handle, 'touchend', 150, 250);
		expect(onDismiss).toHaveBeenCalledTimes(1);
		expect(sheet.style.transform).toBe('');
		expect(sheet.style.transition).toBe('');

		action.destroy();
	});

	it('snaps back without dismissing for small sub-threshold drags', () => {
		const onDismiss = vi.fn();
		const { sheet, handle } = createSheetDom();
		const action = sheetDrag(sheet, { onDismiss });

		dispatchTouch(handle, 'touchstart', 150, 120);
		vi.advanceTimersByTime(300);
		dispatchTouch(handle, 'touchmove', 150, 150);
		expect(sheet.style.transform).toBe('translateY(30px)');

		vi.advanceTimersByTime(300);
		dispatchTouch(handle, 'touchend', 150, 150);
		expect(onDismiss).not.toHaveBeenCalled();
		expect(sheet.style.transform).toBe('translateY(0px)');

		vi.advanceTimersByTime(250);
		expect(sheet.style.transform).toBe('');

		action.destroy();
	});

	it('ignores body drag when scrolled down, and allows body drag when at scrollTop === 0', () => {
		const onDismiss = vi.fn();
		const { sheet, body } = createSheetDom(64);
		const action = sheetDrag(sheet, { onDismiss });

		dispatchTouch(body, 'touchstart', 150, 200);
		dispatchTouch(body, 'touchmove', 150, 360);
		expect(sheet.style.transform).toBe('');
		dispatchTouch(body, 'touchend', 150, 360);
		expect(onDismiss).not.toHaveBeenCalled();

		body.scrollTop = 0;
		dispatchTouch(body, 'touchstart', 150, 200);
		vi.advanceTimersByTime(50);
		dispatchTouch(body, 'touchmove', 150, 340);
		expect(sheet.style.transform).toBe('translateY(140px)');
		dispatchTouch(body, 'touchend', 150, 340);
		expect(onDismiss).toHaveBeenCalledTimes(1);

		action.destroy();
	});

	it('does not activate on desktop viewports', () => {
		Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: 1280 });
		const onDismiss = vi.fn();
		const { sheet, handle } = createSheetDom();
		const action = sheetDrag(sheet, { onDismiss });

		dispatchTouch(handle, 'touchstart', 150, 120);
		dispatchTouch(handle, 'touchmove', 150, 300);
		dispatchTouch(handle, 'touchend', 150, 300);
		expect(sheet.style.transform).toBe('');
		expect(onDismiss).not.toHaveBeenCalled();

		action.destroy();
	});

	it('supports mouse/pointer dragging on the drag handle at mobile widths', () => {
		const onDismiss = vi.fn();
		const { sheet, handle } = createSheetDom();
		const action = sheetDrag(sheet, { onDismiss });

		dispatchPointer(handle, 'pointerdown', 180, 110);
		vi.advanceTimersByTime(40);
		dispatchPointer(window, 'pointermove', 180, 230);
		expect(sheet.style.transform).toBe('translateY(120px)');
		dispatchPointer(window, 'pointerup', 180, 230);
		expect(onDismiss).toHaveBeenCalledTimes(1);

		action.destroy();
	});
});
