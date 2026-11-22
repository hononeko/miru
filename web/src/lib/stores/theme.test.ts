import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ThemeStore } from './theme.svelte';

function createMatchMediaMock(initialDark: boolean) {
	let matches = initialDark;
	const listeners = new Set<(e: MediaQueryListEvent) => void>();

	const mql = {
		get matches() {
			return matches;
		},
		media: '(prefers-color-scheme: dark)',
		onchange: null,
		addEventListener: vi.fn((event: string, cb: (e: MediaQueryListEvent) => void) => {
			if (event === 'change') listeners.add(cb);
		}),
		removeEventListener: vi.fn((event: string, cb: (e: MediaQueryListEvent) => void) => {
			if (event === 'change') listeners.delete(cb);
		}),
		addListener: vi.fn(),
		removeListener: vi.fn(),
		dispatchEvent: vi.fn()
	};

	const matchMediaFn = vi.fn().mockImplementation(() => mql);
	return {
		matchMediaFn,
		mql,
		triggerChange(nextDark: boolean) {
			matches = nextDark;
			const event = { matches: nextDark, media: '(prefers-color-scheme: dark)' } as MediaQueryListEvent;
			for (const cb of listeners) {
				cb(event);
			}
		}
	};
}

function createStorageMock(): Storage {
	const map = new Map<string, string>();
	return {
		get length() {
			return map.size;
		},
		clear: () => map.clear(),
		getItem: (key: string) => map.get(key) ?? null,
		key: (index: number) => Array.from(map.keys())[index] ?? null,
		removeItem: (key: string) => {
			map.delete(key);
		},
		setItem: (key: string, value: string) => {
			map.set(key, String(value));
		}
	};
}

describe('ThemeStore', () => {
	let store: ThemeStore | null = null;
	let storage: Storage;

	beforeEach(() => {
		storage = createStorageMock();
		vi.stubGlobal('localStorage', storage);
		Object.defineProperty(window, 'localStorage', { value: storage, configurable: true });
		document.documentElement.classList.remove('dark');
		document.head.innerHTML =
			'<meta name="color-scheme" content="light dark"><meta name="theme-color" content="#090A0F">';
	});

	afterEach(() => {
		store?.destroy();
		store = null;
		vi.restoreAllMocks();
	});

	it('defaults to system mode and tracks matchMedia changes when in system mode', () => {
		const mm = createMatchMediaMock(false);
		vi.stubGlobal('matchMedia', mm.matchMediaFn);
		Object.defineProperty(window, 'matchMedia', { value: mm.matchMediaFn, configurable: true });

		store = new ThemeStore();
		expect(store.mode).toBe('system');
		expect(store.systemTheme).toBe('light');
		expect(store.resolved).toBe('light');
		expect(document.documentElement.classList.contains('dark')).toBe(false);
		expect(document.querySelector('meta[name="color-scheme"]')?.getAttribute('content')).toBe('light dark');

		// System switches to dark -> follows system
		mm.triggerChange(true);
		expect(store.systemTheme).toBe('dark');
		expect(store.resolved).toBe('dark');
		expect(document.documentElement.classList.contains('dark')).toBe(true);
		expect(document.querySelector('meta[name="theme-color"]')?.getAttribute('content')).toBe('#090A0F');

		// Pinning light ignores subsequent system changes
		store.set('light');
		expect(localStorage.getItem('miru-theme')).toBe('light');
		expect(document.documentElement.classList.contains('dark')).toBe(false);
		expect(document.querySelector('meta[name="color-scheme"]')?.getAttribute('content')).toBe('light');

		mm.triggerChange(false);
		mm.triggerChange(true);
		expect(store.mode).toBe('light');
		expect(store.resolved).toBe('light');
		expect(document.documentElement.classList.contains('dark')).toBe(false);

		// Returning to system removes localStorage override and applies current system theme
		store.set('system');
		expect(localStorage.getItem('miru-theme')).toBeNull();
		expect(store.resolved).toBe('dark');
		expect(document.documentElement.classList.contains('dark')).toBe(true);
	});

	it('hydrates stored theme from localStorage and supports toggle()', () => {
		const mm = createMatchMediaMock(false);
		Object.defineProperty(window, 'matchMedia', { value: mm.matchMediaFn, configurable: true });
		localStorage.setItem('miru-theme', 'dark');

		store = new ThemeStore();
		expect(store.mode).toBe('dark');
		expect(store.resolved).toBe('dark');
		expect(document.documentElement.classList.contains('dark')).toBe(true);

		store.toggle();
		expect(store.mode).toBe('light');
		expect(document.documentElement.classList.contains('dark')).toBe(false);

		store.toggle();
		expect(store.mode).toBe('dark');
		expect(document.documentElement.classList.contains('dark')).toBe(true);
	});
});
