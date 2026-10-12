import { defineConfig } from '@playwright/test';
import path from 'node:path';

export default defineConfig({
  testDir: './tests/recovery',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 600_000,
  outputDir: path.join(process.env.RECOVERY_RESULTS_DIR ?? 'results/recovery', 'artifacts'),
  expect: { timeout: 120_000 },
  reporter: [
    ['list'],
    ['junit', { outputFile: path.join(process.env.RECOVERY_RESULTS_DIR ?? 'results/recovery', 'junit.xml') }]
  ],
  use: { trace: 'off' }
});
