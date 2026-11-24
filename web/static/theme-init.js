(function () {
	try {
		var stored = localStorage.getItem('miru-theme');
		var mode = stored === 'light' || stored === 'dark' || stored === 'system' ? stored : 'system';
		var prefersDark =
			window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
		var prefersLight =
			window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches;
		var isDark =
			mode === 'dark' || (mode === 'system' && (prefersDark || !prefersLight));
		document.documentElement.classList.toggle('dark', isDark);
		var csMeta = document.querySelector('meta[name="color-scheme"]');
		if (csMeta) csMeta.setAttribute('content', mode === 'system' ? 'light dark' : mode);
		var tcMeta = document.querySelector('meta[name="theme-color"]');
		if (tcMeta) tcMeta.setAttribute('content', isDark ? '#090A0F' : '#F9FAFC');
	} catch {
		document.documentElement.classList.add('dark');
	}
})();
