import { describe, expect, it } from 'vitest';
import { jellyfinQuickConnectUrl } from './jellyfin';

describe('jellyfinQuickConnectUrl', () => {
	it('links to the Quick Connect page of the web app with the code filled in', () => {
		expect(jellyfinQuickConnectUrl('http://10.10.1.132:8096', '918273')).toBe(
			'http://10.10.1.132:8096/web/#/quickconnect?code=918273'
		);
	});

	it('keeps a base path and drops trailing slashes', () => {
		expect(jellyfinQuickConnectUrl('https://media.example.com/jellyfin/', '123456')).toBe(
			'https://media.example.com/jellyfin/web/#/quickconnect?code=123456'
		);
		expect(jellyfinQuickConnectUrl('  https://jellyfin.example.com//  ', '123456')).toBe(
			'https://jellyfin.example.com/web/#/quickconnect?code=123456'
		);
	});

	it('ignores a query or fragment in the server URL', () => {
		expect(jellyfinQuickConnectUrl('http://jf.local:8096/?x=1#/home', '42')).toBe(
			'http://jf.local:8096/web/#/quickconnect?code=42'
		);
	});

	it('encodes the code', () => {
		expect(jellyfinQuickConnectUrl('http://jf.local:8096', ' 12 34&x ')).toBe(
			'http://jf.local:8096/web/#/quickconnect?code=12%2034%26x'
		);
	});

	it('returns null for anything that is not an http(s) URL', () => {
		expect(jellyfinQuickConnectUrl('javascript:alert(1)', '1')).toBeNull();
		expect(jellyfinQuickConnectUrl('ftp://jf.local', '1')).toBeNull();
		expect(jellyfinQuickConnectUrl('jf.local:8096', '1')).toBeNull();
		expect(jellyfinQuickConnectUrl('', '1')).toBeNull();
	});
});
