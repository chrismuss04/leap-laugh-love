import { defineConfig, devices } from '@playwright/test';

// Optional local overrides (BASE_URL etc.); CI passes real environment variables instead.
try {
  process.loadEnvFile('.env');
} catch {
  // No .env - the defaults below apply.
}

const CI = !!process.env.CI;
const BASE_URL = process.env.BASE_URL ?? 'http://localhost:4200';
// Chromium alone by default - in CI it is the only browser installed. Set E2E_ALL_BROWSERS=1
// (after `npx playwright install firefox webkit`) for a cross-browser pass before a release.
const ALL_BROWSERS = !!process.env.E2E_ALL_BROWSERS;

/** Browser projects share everything but the device; API specs run once, not per browser. */
const browserProject = {
  testIgnore: /tests[\\/]api[\\/]/,
  dependencies: ['setup']
};

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: CI,
  retries: CI ? 1 : 0,
  // The CI agent also runs Postgres, four JVMs and the Angular dev server; two browsers at a
  // time is what it can carry. (Each worker trades from its own seeded account, max 16.)
  workers: CI ? 2 : undefined,
  timeout: 60_000,
  expect: { timeout: 10_000 },

  reporter: CI
    ? [['list'], ['html', { open: 'never' }], ['junit', { outputFile: 'results/junit.xml' }]]
    : [['list'], ['html', { open: 'on-failure' }]],

  use: {
    baseURL: BASE_URL,
    // A trace (DOM snapshots, network, console) is enough to debug a failure and far cheaper
    // than recording video of every test.
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    // The app formats money and dates with en-US; pin it so every browser renders the same text.
    locale: 'en-US',
    timezoneId: 'UTC'
  },

  projects: [
    // Confirms the stack is up and seeded before anything else runs, with a message that says
    // which service is missing rather than every test timing out.
    { name: 'setup', testMatch: /.*\.setup\.ts/ },
    { name: 'api', testMatch: /tests[\\/]api[\\/].*\.spec\.ts/, dependencies: ['setup'] },
    { name: 'chromium', use: { ...devices['Desktop Chrome'] }, ...browserProject },
    ...(ALL_BROWSERS
      ? [
          { name: 'firefox', use: { ...devices['Desktop Firefox'] }, ...browserProject },
          { name: 'webkit', use: { ...devices['Desktop Safari'] }, ...browserProject }
        ]
      : [])
  ]
});
