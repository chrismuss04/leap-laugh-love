import { defineConfig, devices } from '@playwright/test';

// Optional local overrides (BASE_URL etc.); CI passes real environment variables instead.
try {
  process.loadEnvFile('.env');
} catch {
  // No .env - the defaults below apply.
}

const CI = !!process.env.CI;
const BASE_URL = process.env.BASE_URL ?? 'http://localhost:4200';
// Pixel baselines are OS-specific and the committed ones come from the Linux Playwright image, so
// the visual project only runs where E2E_VISUAL is set - `npm run test:visual` and the CI container.
const VISUAL = !!process.env.E2E_VISUAL;

/** Browser projects share everything but the device; API specs run once, not per browser. */
const browserProject = {
  testIgnore: /tests[\\/](api|visual)[\\/]/,
  dependencies: ['setup']
};

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: CI,
  retries: CI ? 2 : 0,
  // Each worker trades from its own seeded account (e2e.trader.00-15), so this can't exceed 16.
  workers: CI ? 4 : undefined,
  // Live prices tick once a second and the first dashboard load fans out ~20 requests, so allow
  // more than the 30s default for the flows that place and then verify an order.
  timeout: 60_000,
  expect: { timeout: 10_000 },

  reporter: CI
    ? [['list'], ['html', { open: 'never' }], ['junit', { outputFile: 'results/junit.xml' }]]
    : [['list'], ['html', { open: 'on-failure' }]],

  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    // The app formats money and dates with en-US; pin it so every browser renders the same text.
    locale: 'en-US',
    timezoneId: 'UTC'
  },

  projects: [
    // Confirms the stack is up and seeded before anything else runs, with a message that says
    // which service is missing rather than 150 identical timeouts.
    { name: 'setup', testMatch: /.*\.setup\.ts/ },

    { name: 'api', testMatch: /tests[\\/]api[\\/].*\.spec\.ts/, dependencies: ['setup'] },

    { name: 'chromium', use: { ...devices['Desktop Chrome'] }, ...browserProject },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] }, ...browserProject },
    { name: 'webkit', use: { ...devices['Desktop Safari'] }, ...browserProject },

    // Phones run the critical path only.
    { name: 'mobile-chrome', use: { ...devices['Pixel 7'] }, ...browserProject, grep: /@smoke/ },
    { name: 'mobile-safari', use: { ...devices['iPhone 14'] }, ...browserProject, grep: /@smoke/ },

    // One engine only: rendering legitimately differs between them.
    ...(VISUAL
      ? [{ name: 'visual', testMatch: /tests[\\/]visual[\\/].*\.spec\.ts/, use: { ...devices['Desktop Chrome'] }, dependencies: ['setup'] }]
      : [])
  ]
});
