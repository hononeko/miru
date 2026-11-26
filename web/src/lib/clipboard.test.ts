import { afterEach, describe, expect, it, vi } from 'vitest';
import { copyTextWhenReady } from './clipboard';

class FakeClipboardItem {
	constructor(readonly items: Record<string, Promise<Blob>>) {}
}

function stubClipboard(clipboard: Partial<Clipboard> | undefined) {
	Object.defineProperty(navigator, 'clipboard', { configurable: true, value: clipboard });
}

describe('copyTextWhenReady', () => {
	const originalClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');

	afterEach(() => {
		vi.unstubAllGlobals();
		if (originalClipboard) Object.defineProperty(navigator, 'clipboard', originalClipboard);
		else delete (navigator as { clipboard?: Clipboard }).clipboard;
	});

	it('writes a ClipboardItem right away whose text arrives later', async () => {
		vi.stubGlobal('ClipboardItem', FakeClipboardItem);
		let written: FakeClipboardItem | undefined;
		const write = vi.fn(async (items: FakeClipboardItem[]) => {
			written = items[0];
			// Like Safari: the write itself waits for the promised data
			await written.items['text/plain'];
		});
		const writeText = vi.fn();
		stubClipboard({ write, writeText } as unknown as Clipboard);

		let answer!: (text: string) => void;
		const result = copyTextWhenReady(new Promise((resolve) => (answer = resolve)));

		// The write starts before the text exists, i.e. still inside the user's click
		expect(write).toHaveBeenCalledTimes(1);
		answer('918273');

		expect(await result).toBe(true);
		expect(await (await written!.items['text/plain']).text()).toBe('918273');
		expect(writeText).not.toHaveBeenCalled();
	});

	it('falls back to writeText when the browser rejects a promised ClipboardItem', async () => {
		vi.stubGlobal('ClipboardItem', FakeClipboardItem);
		const write = vi.fn().mockRejectedValue(new DOMException('Not allowed', 'NotAllowedError'));
		const writeText = vi.fn().mockResolvedValue(undefined);
		stubClipboard({ write, writeText } as unknown as Clipboard);

		expect(await copyTextWhenReady(Promise.resolve('555666'))).toBe(true);
		expect(writeText).toHaveBeenCalledWith('555666');
	});

	it('uses writeText when ClipboardItem is not available', async () => {
		vi.stubGlobal('ClipboardItem', undefined);
		const writeText = vi.fn().mockResolvedValue(undefined);
		stubClipboard({ writeText } as unknown as Clipboard);

		expect(await copyTextWhenReady(Promise.resolve('777888'))).toBe(true);
		expect(writeText).toHaveBeenCalledWith('777888');
	});

	it('reports false when the browser refuses the copy', async () => {
		vi.stubGlobal('ClipboardItem', undefined);
		stubClipboard({ writeText: vi.fn().mockRejectedValue(new DOMException('Not allowed', 'NotAllowedError')) } as unknown as Clipboard);

		expect(await copyTextWhenReady(Promise.resolve('1'))).toBe(false);
	});

	it('reports false without a Clipboard API, as on plain http', async () => {
		stubClipboard(undefined);

		expect(await copyTextWhenReady(Promise.resolve('1'))).toBe(false);
	});

	it('reports false, with no unhandled rejection, when the text never arrives', async () => {
		vi.stubGlobal('ClipboardItem', FakeClipboardItem);
		const write = vi.fn(async (items: FakeClipboardItem[]) => {
			await items[0].items['text/plain'];
		});
		const writeText = vi.fn();
		stubClipboard({ write, writeText } as unknown as Clipboard);
		const unhandled = vi.fn();
		process.on('unhandledRejection', unhandled);

		try {
			expect(await copyTextWhenReady(Promise.reject(new Error('start failed')))).toBe(false);
			await new Promise((resolve) => setTimeout(resolve, 0));
			expect(writeText).not.toHaveBeenCalled();
			expect(unhandled).not.toHaveBeenCalled();
		} finally {
			process.off('unhandledRejection', unhandled);
		}
	});
});
