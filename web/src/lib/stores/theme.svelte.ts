import { browser } from '$app/environment';

export type ThemeMode = 'dark' | 'light';

const STORAGE_KEY = 'miru-theme';

function detectInitialTheme(): ThemeMode {
	if (!browser) return 'dark';
	try {
		const stored = localStorage.getItem(STORAGE_KEY);
		if (stored === 'dark' || stored === 'light') return stored;
		if (window.matchMedia?.('(prefers-color-scheme: light)').matches) return 'light';
	} catch {
		// Ignore storage access errors in restricted webviews
	}
	return 'dark';
}

function applyThemeToDom(mode: ThemeMode) {
	if (!browser) return;
	document.documentElement.classList.toggle('dark', mode === 'dark');
	const meta = document.querySelector('meta[name="theme-color"]');
	if (meta) {
		meta.setAttribute('content', mode === 'dark' ? '#090A0F' : '#F9FAFC');
	}
}

class ThemeStore {
	mode = $state<ThemeMode>(detectInitialTheme());

	constructor() {
		applyThemeToDom(this.mode);
	}

	set(mode: ThemeMode) {
		this.mode = mode;
		applyThemeToDom(mode);
		if (browser) {
			try {
				localStorage.setItem(STORAGE_KEY, mode);
			} catch {
				// ignore
			}
		}
	}

	toggle() {
		this.set(this.mode === 'dark' ? 'light' : 'dark');
	}
}

export const themeStore = new ThemeStore();
