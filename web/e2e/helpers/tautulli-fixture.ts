import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = path.dirname(fileURLToPath(import.meta.url));
const creatorScript = path.join(currentDir, 'create-tautulli-db.mjs');

export interface TautulliFixture {
	readonly filePath: string;
	readonly expectedRows: number;
	readonly expectedUsers: number;
	cleanup(): void;
}

export function createTautulliFixture(targetDir?: string): TautulliFixture {
	const ownedDir = !targetDir;
	const dir = targetDir ?? fs.mkdtempSync(path.join(os.tmpdir(), 'miru-e2e-tautulli-'));
	const filePath = path.join(dir, 'tautulli.db');
	execFileSync('bun', [creatorScript, filePath], { stdio: 'pipe' });

	return {
		filePath,
		expectedRows: 30,
		expectedUsers: 2,
		cleanup() {
			if (ownedDir) {
				fs.rmSync(dir, { recursive: true, force: true });
			} else if (fs.existsSync(filePath)) {
				fs.rmSync(filePath, { force: true });
			}
		}
	};
}
