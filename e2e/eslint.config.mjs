import tseslint from 'typescript-eslint';
import playwright from 'eslint-plugin-playwright';

export default tseslint.config(
  { ignores: ['node_modules/', 'playwright-report/', 'test-results/', 'results/'] },
  ...tseslint.configs.recommended,
  {
    ...playwright.configs['flat/recommended'],
    files: ['tests/**/*.ts', 'pages/**/*.ts', 'fixtures/**/*.ts'],
    // Our fixtures export these as well as `test`.
    settings: { playwright: { globalAliases: { test: ['setup', 'apiTest'] } } },
    rules: {
      ...playwright.configs['flat/recommended'].rules,
      // The price stream keeps a request open for as long as the page lives, so 'networkidle'
      // never arrives. Wait on an assertion or a specific response instead.
      'playwright/no-networkidle': 'error',
      // Fixed sleeps are the main source of flaky E2E suites; every wait here is on a condition.
      'playwright/no-wait-for-timeout': 'error',
      'playwright/no-focused-test': 'error',
      // Page objects hold the assertions for common flows.
      'playwright/expect-expect': ['warn', { assertFunctionPatterns: ['^expect', '\.expect[A-Z]'] }]
    }
  }
);
