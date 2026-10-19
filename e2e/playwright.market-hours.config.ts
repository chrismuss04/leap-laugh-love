import { defineConfig, devices } from '@playwright/test';

// Optional local overrides (BASE_URL etc.); CI passes real environment variables instead.
try {
  process.loadEnvFile('.env');
} catch {
  // No .env - the defaults below apply.
}

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:4200';

/**
 * Market hours: orders and prices outside regular trading. These specs need a stack pinned to one
 * session - the normal suite's stack trades around the clock - so they have a config of their own,
 * like the recovery suite. Start the stack as "Market hours tests" in e2e/README.md describes, e.g.
 * MARKET_HOURS_FORCE_SESSION=AFTER_HOURS, then:
 *
 *   npm run test:market-hours
 *
 * Each spec file applies to one session and skips itself on any other, so running against a stack
 * in the wrong session is harmless.
 */
export default defineConfig({
  testDir: './tests/market-hours',
  // Alphabetical, one after another: the opening spec waits for the market to open, and the specs
  // before it must be done inside the minutes the stack is closed for.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report/market-hours' }]],
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    locale: 'en-US',
    timezoneId: 'UTC'
  },
  projects: [
    // The same stack check as the main suite: services up, seed loaded, quotes fresh.
    { name: 'setup', testDir: './tests', testMatch: /stack\.setup\.ts/ },
    { name: 'market-hours', use: { ...devices['Desktop Chrome'] }, dependencies: ['setup'] }
  ]
});
