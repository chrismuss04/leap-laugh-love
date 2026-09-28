# End-to-end tests (Playwright)

Browser and API tests against the whole running stack: the Angular frontend and all four
services, through the same `/api/**` dev-server proxy a browser uses. They complement the Maven
tests, which cover each service on its own.

| Project | What runs |
| --- | --- |
| `setup` | Checks every service is up, the E2E seed is loaded and market data is ticking. Everything else depends on it. |
| `api` | Auth on every endpoint, clients isolated from each other, order and cash rules. Once, no browser. |
| `chromium` · `firefox` · `webkit` | The full UI suite. |
| `mobile-chrome` · `mobile-safari` | `@smoke` tests on Pixel 7 / iPhone 14 viewports. |
| `visual` | Screenshot comparisons. Opt-in (`E2E_VISUAL=1`), Chromium in the Linux Playwright image only. |

## Running locally

1. **Start the stack with the E2E seed.**
   - Docker: `docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build` from the
     repo root. The seed only loads into a fresh volume, so run `docker compose down -v` first if
     the database already exists.
   - Windows without Docker: `.\scripts\start-local.ps1`, then `.\e2e\scripts\seed-local.ps1`
     (safe to re-run).
2. **Install once:** `cd e2e && npm install && npx playwright install`
3. **Run:**

| Command | |
| --- | --- |
| `npm test` | Everything except `visual` |
| `npm run test:smoke` | Critical path only, every browser |
| `npm run test:chromium` | One browser, fastest feedback |
| `npm run test:api` | API project only |
| `npm run test:ui` | Playwright UI mode: watch, pick and debug tests |
| `npm run report` | Open the last HTML report (traces, videos, screenshots) |
| `npm run test:visual` / `test:visual:update` | Compare / regenerate screenshot baselines in Docker |

`BASE_URL` (default `http://localhost:4200`) points the suite elsewhere; put it in `e2e/.env`.

## How the suite stays independent

- **Each worker trades from its own account.** `e2e/seed/e2e_seed.sql` creates
  `e2e.trader.00`–`15@leap.test`, each with $1,000,000. The `trader` fixture picks one by
  Playwright's parallel index, so parallel tests never share balances or positions. That caps
  workers at 16.
- **Nothing is cleaned up, because it can't be.** Orders, executions and the cash ledger are
  append-only at the database level. Tests assert before/after differences, never absolute
  balances, and are safe to re-run against the same database indefinitely.
- **Shared personas are read-only.** `alice` (seeded demo data), `history` (25 orders for
  pagination), `multi` (two accounts), `noAccount`, and `grace` (seeded as locked). Anything that
  registers, fails logins or needs a known position uses a trader or a freshly registered user.
- **State is set up through the API.** A sell test buys its shares with `trader.api.placeOrder`
  rather than clicking through a buy first. Signing in injects the JWT into `localStorage` rather
  than using the form; the form has its own specs.
- **Failures the backend won't produce on demand** (a crash mid-order, an ACCEPTED-but-unfilled
  order, a service outage) are simulated with `page.route`.

## Writing tests

- Import `test` / `expect` from `fixtures/test` (or `apiTest` for API-only specs). Choose who is
  signed in with `test.use({ persona: 'trader' | 'alice' | ... })`; the default is signed out.
- Locate by role and label first (`getByRole('button', { name: 'Buy AAPL' })`). Fall back to
  `data-testid` only where there is no accessible handle. The frontend has a handful of these
  for figures and table rows.
- **Never wait for `networkidle`**, because the live price stream keeps a request open forever.
  Don't use fixed sleeps either. ESLint rejects both. Wait on an assertion or a specific response.
- **Prices change every second.** Assert formats and relationships (`estimate == price × shares`,
  `buying power dropped by the fill total`), never specific prices.
- Every test fails on uncaught page errors and unexpected `console.error`. A test that makes a
  request fail on purpose lists it with `test.use({ allowedConsoleErrors: [/pattern/] })`.
- Tag the critical path `@smoke`. Pull requests run only those; `main` and the nightly build run
  everything.

## Known issues the suite documents

These tests describe the correct behaviour and are marked `test.fail()`, or have a comment
explaining a workaround, until the app is fixed:

- **Profile menu doesn't close on an outside click.** The backdrop is `position: fixed` inside a
  header whose `backdrop-filter` becomes its containing block, so it covers only the header
  (`navigation/shell.spec.ts`).
- **Symbol search can close under the user.** `onBlur()` schedules a close 150ms later and
  `onFocus()` doesn't cancel it. Typing doesn't reopen the list; only refocusing does
  (`pages/symbol-search.ts` retries around it).
- **Colour contrast** is below WCAG AA for muted text on every page. It is baselined in
  `a11y/accessibility.spec.ts`, and reported on each test rather than failing it.

Other things the suite surfaced, which it works around without asserting on them:

- **Sign-in reloads the page a second after the dashboard renders.** Storing the token already
  swaps in the app, and then `window.location.href = '/dashboard'` fires a second full load. The
  "Sign in successful! Redirecting..." message is never visible.
- **The submit buttons lose their accessible name while loading.** The label is replaced by a
  bare spinner, so the page objects locate them by `type="submit"`.
- **The "Sign up" / "Sign in" footer links are `<a>` without `href`,** so they have no link role
  and can't be reached with the keyboard.

## CI

The Jenkins `E2E` stage starts the `frontend` service next to the backend, then runs this suite
in `mcr.microsoft.com/playwright:v<version>-noble` on the compose network. The version is read from
`package.json`, so bumping `@playwright/test` bumps the image. The JUnit results feed the build's
test report. The HTML report, traces and videos are archived as build artifacts.
