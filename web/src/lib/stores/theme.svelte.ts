export type ThemeMode = 'system' | 'dark' | 'light';
export type ResolvedTheme = 'dark' | 'light';

const STORAGE_KEY = 'miru-theme';
const DARK_MEDIA_QUERY = '(prefers-color-scheme: dark)';

function hasDom(): boolean {
	return typeof window !== 'undefined' && typeof document !== 'undefined';
}

function detectSystemTheme(): ResolvedTheme {
	if (!hasDom() || typeof window.matchMedia !== 'function') return 'dark';
	try {
		return window.matchMedia(DARK_MEDIA_QUERY).matches ? 'dark' : 'light';
	} catch {
		return 'dark';
	}
}

function getStorage(): Storage | null {
	if (!hasDom()) return null;
	const ls = window.localStorage;
	if (!ls || typeof ls.getItem !== 'function' || typeof ls.setItem !== 'function') return null;
	return ls;
}

function detectInitialTheme(): ThemeMode {
	const storage = getStorage();
	if (!storage) return 'system';
	try {
		const stored = storage.getItem(STORAGE_KEY);
		if (stored === 'dark' || stored === 'light' || stored === 'system') {
			return stored;
		}
	} catch {
		// Ignore storage access errors in restricted webviews
	}
	return 'system';
}

function resolveTheme(mode: ThemeMode, systemTheme: ResolvedTheme): ResolvedTheme {
	return mode === 'system' ? systemTheme : mode;
}

function applyThemeToDom(mode: ThemeMode, systemTheme: ResolvedTheme) {
	if (!hasDom()) return;
	const resolved = resolveTheme(mode, systemTheme);
	document.documentElement.classList.toggle('dark', resolved === 'dark');

	const colorSchemeMeta = document.querySelector('meta[name="color-scheme"]');
	if (colorSchemeMeta) {
		colorSchemeMeta.setAttribute('content', mode === 'system' ? 'light dark' : mode);
	}

	const themeColorMeta = document.querySelector('meta[name="theme-color"]');
	if (themeColorMeta) {
		themeColorMeta.setAttribute('content', resolved === 'dark' ? '#030705' : '#F5F7F5');
	}
}

export class ThemeStore {
	mode = $state<ThemeMode>(detectInitialTheme());
	systemTheme = $state<ResolvedTheme>(detectSystemTheme());
	resolved = $derived<ResolvedTheme>(resolveTheme(this.mode, this.systemTheme));

	private mediaQuery: MediaQueryList | null = null;
	private mediaListener: ((e: MediaQueryListEvent) => void) | null = null;
	private storageListener: ((e: StorageEvent) => void) | null = null;

	constructor() {
		applyThemeToDom(this.mode, this.systemTheme);
		this.attachListeners();
	}

	private attachListeners() {
		if (!hasDom()) return;

		if (typeof window.matchMedia === 'function') {
			try {
				const mql = window.matchMedia(DARK_MEDIA_QUERY);
				this.mediaQuery = mql;
				this.mediaListener = (e: MediaQueryListEvent) => {
					this.systemTheme = e.matches ? 'dark' : 'light';
					if (this.mode === 'system') {
						applyThemeToDom(this.mode, this.systemTheme);
					}
				};
				if (typeof mql.addEventListener === 'function') {
					mql.addEventListener('change', this.mediaListener);
				}
			} catch {
				// Ignore matchMedia errors in non-standard environments
			}
		}

		this.storageListener = (e: StorageEvent) => {
			if (e.key !== STORAGE_KEY) return;
			const next: ThemeMode =
				e.newValue === 'dark' || e.newValue === 'light' || e.newValue === 'system'
					? e.newValue
					: 'system';
			this.mode = next;
			applyThemeToDom(this.mode, this.systemTheme);
		};
		window.addEventListener('storage', this.storageListener);
	}

	set(mode: ThemeMode) {
		this.mode = mode;
		applyThemeToDom(this.mode, this.systemTheme);
		const storage = getStorage();
		if (storage) {
			try {
				if (mode === 'system') {
					storage.removeItem(STORAGE_KEY);
				} else {
					storage.setItem(STORAGE_KEY, mode);
				}
			} catch {
				// Ignore storage access errors in restricted webviews
			}
		}
	}

	toggle() {
		this.set(this.resolved === 'dark' ? 'light' : 'dark');
	}

	destroy() {
		if (this.mediaQuery && this.mediaListener && typeof this.mediaQuery.removeEventListener === 'function') {
			this.mediaQuery.removeEventListener('change', this.mediaListener);
		}
		if (hasDom() && this.storageListener) {
			window.removeEventListener('storage', this.storageListener);
		}
		this.mediaQuery = null;
		this.mediaListener = null;
		this.storageListener = null;
	}
}

export const themeStore = new ThemeStore();
