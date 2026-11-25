import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { lockPageScroll } from '$lib/scrollLock';
import { modalDialog } from './modalDialog';

const INSIDE = { clientX: 300, clientY: 200 };
const OUTSIDE = { clientX: 20, clientY: 200 };

function createDialog() {
	const dialog = document.createElement('dialog');
	const button = document.createElement('button');
	dialog.appendChild(button);
	document.body.appendChild(dialog);
	dialog.getBoundingClientRect = () => ({ left: 100, right: 500, top: 100, bottom: 300 }) as DOMRect;
	dialog.close = vi.fn(() => dialog.removeAttribute('open'));
	dialog.setAttribute('open', '');
	return { dialog, button };
}

function send(target: EventTarget, type: string, point: { clientX: number; clientY: number }) {
	target.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, ...point }));
}

const isLocked = () => document.body.hasAttribute('data-scroll-locked');

describe('modalDialog', () => {
	let action: ReturnType<typeof modalDialog> | null = null;

	beforeEach(() => {
		document.body.innerHTML = '';
	});

	afterEach(() => {
		action?.destroy();
		action = null;
	});

	it('closes on the click of a backdrop press, not on its pointerdown or pointerup', () => {
		const { dialog } = createDialog();
		action = modalDialog(dialog);

		send(dialog, 'pointerdown', OUTSIDE);
		send(dialog, 'pointerup', OUTSIDE);
		expect(dialog.close).not.toHaveBeenCalled();

		send(dialog, 'click', OUTSIDE);
		expect(dialog.close).toHaveBeenCalledTimes(1);
	});

	it('stays open for clicks inside it and for drags that cross its edge', () => {
		const { dialog, button } = createDialog();
		action = modalDialog(dialog);

		send(button, 'pointerdown', INSIDE);
		send(button, 'click', INSIDE);
		// Pressing Enter on a button reports a click at 0,0
		send(button, 'click', { clientX: 0, clientY: 0 });

		send(button, 'pointerdown', INSIDE);
		send(dialog, 'click', OUTSIDE);

		send(dialog, 'pointerdown', OUTSIDE);
		send(dialog, 'click', INSIDE);

		expect(dialog.close).not.toHaveBeenCalled();
	});

	it('stays open for a backdrop press that starts while it must not be dismissed', () => {
		const { dialog } = createDialog();
		let dismissable = false;
		action = modalDialog(dialog, { canDismiss: () => dismissable });

		send(dialog, 'pointerdown', OUTSIDE);
		// The press itself may lift the reason, but only the next one closes the dialog
		dismissable = true;
		send(dialog, 'click', OUTSIDE);
		expect(dialog.close).not.toHaveBeenCalled();

		send(dialog, 'pointerdown', OUTSIDE);
		send(dialog, 'click', OUTSIDE);
		expect(dialog.close).toHaveBeenCalledTimes(1);
	});

	it('blocks touches that would scroll the page because nothing under them scrolls inside the dialog', () => {
		const { dialog, button } = createDialog();
		const list = document.createElement('div');
		list.style.overflowY = 'auto';
		const row = document.createElement('p');
		const slider = document.createElement('input');
		list.appendChild(row);
		dialog.append(list, slider);
		const setContentHeight = (height: number) => {
			Object.defineProperty(list, 'clientHeight', { configurable: true, value: 200 });
			Object.defineProperty(list, 'scrollHeight', { configurable: true, value: height });
		};
		action = modalDialog(dialog);

		const blocks = (target: Element, touches = 1) => {
			const move = new Event('touchmove', { bubbles: true, cancelable: true });
			for (const event of [new Event('touchstart', { bubbles: true }), move]) {
				Object.defineProperty(event, 'touches', { value: Array(touches).fill({}) });
				target.dispatchEvent(event);
			}
			return move.defaultPrevented;
		};

		expect(blocks(dialog)).toBe(true);
		expect(blocks(button)).toBe(true);
		expect(blocks(slider)).toBe(false);

		setContentHeight(900);
		expect(blocks(row)).toBe(false);
		setContentHeight(200);
		expect(blocks(row)).toBe(true);

		// Pinch zoom still works
		expect(blocks(button, 2)).toBe(false);
	});

	it('locks page scroll while the dialog is open and unlocks it on close or destroy', async () => {
		const { dialog } = createDialog();
		dialog.removeAttribute('open');
		action = modalDialog(dialog);
		expect(isLocked()).toBe(false);

		dialog.setAttribute('open', '');
		await vi.waitFor(() => expect(isLocked()).toBe(true));

		dialog.close();
		await vi.waitFor(() => expect(isLocked()).toBe(false));

		dialog.setAttribute('open', '');
		await vi.waitFor(() => expect(isLocked()).toBe(true));
		action.destroy();
		expect(isLocked()).toBe(false);
	});
});

describe('lockPageScroll', () => {
	it('stays locked until every lock is released, ignoring repeated releases', () => {
		const releaseFirst = lockPageScroll();
		const releaseSecond = lockPageScroll();
		releaseFirst();
		releaseFirst();
		expect(isLocked()).toBe(true);
		releaseSecond();
		expect(isLocked()).toBe(false);
	});

	it('keeps the gutter of a scrollbar that a closing bits-ui dialog has already hidden', () => {
		vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(window.innerWidth);
		document.body.style.setProperty('--scrollbar-width', '15px');
		const release = lockPageScroll();
		expect(document.body.style.getPropertyValue('--scroll-lock-gutter')).toBe('15px');
		release();
		expect(document.body.style.getPropertyValue('--scroll-lock-gutter')).toBe('');
		document.body.style.removeProperty('--scrollbar-width');
		vi.restoreAllMocks();
	});
});
