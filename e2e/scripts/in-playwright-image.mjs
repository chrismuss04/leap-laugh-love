// Runs a command inside the official Playwright image whose version matches the installed
// @playwright/test - the same Linux environment CI uses. Screenshot baselines differ between
// operating systems (fonts, anti-aliasing), so they are only generated and compared in here.
//
//   node scripts/in-playwright-image.mjs npx playwright test --project=setup --project=visual
//
// BASE_URL defaults to the host's frontend as seen from a container.
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const e2eDir = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const { version } = JSON.parse(readFileSync(resolve(e2eDir, 'node_modules/@playwright/test/package.json'), 'utf8'));
const image = `mcr.microsoft.com/playwright:v${version}-noble`;
const baseUrl = process.env.BASE_URL ?? 'http://host.docker.internal:4200';

const args = [
  'run', '--rm', '--ipc=host',
  '--add-host=host.docker.internal:host-gateway',
  '-e', `BASE_URL=${baseUrl}`,
  '-e', 'E2E_VISUAL=1',
  '-e', 'CI=1',
  // A separate node_modules for Linux; the host's may hold Windows/macOS binaries.
  '-v', `${e2eDir}:/e2e`,
  '-v', 'leap-e2e-node-modules:/e2e/node_modules',
  '-w', '/e2e',
  image,
  'sh', '-c', `npm ci --no-audit --no-fund >/dev/null && ${process.argv.slice(2).join(' ')}`
];

console.log(`> docker run ${image} ${process.argv.slice(2).join(' ')}`);
const result = spawnSync('docker', args, { stdio: 'inherit' });
process.exit(result.status ?? 1);
