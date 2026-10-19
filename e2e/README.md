# End-to-end tests (Playwright)

A small suite of end-to-end tests for the critical paths, run against the whole stack: the
Angular frontend and all four services, reached through the same `/api/**` dev-server proxy a
browser uses. It complements the Maven tests, which cover each service on its own, and the
Angular unit tests, which are the place for field-level validation and component behaviour.

**What belongs here.** A test earns a place if its failure means a client could lose money, lose
access, or be unable to use a core flow: signing in, registering, placing and selling orders, and
seeing the result in order history and holdings. Cosmetic behaviour and single-field validation
don't belong here; they are cheaper and faster as unit tests.

| Project | What runs |
| --- | --- |
| `setup` | Checks every service is up, the E2E seed is loaded and market data is ticking. Everything else depends on it. |
| `api` | Auth on every endpoint, clients isolated from each other, order and cash rules. No browser. |
| `chromium` | The UI tests. |
| `firefox` · `webkit` | Same UI tests. Opt-in with `npm run test:all-browsers`, e.g. before a release. |

## Running locally

1. **Start the stack with the E2E seed.**
   - Docker: `docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build` from the
     repo root. The seed only loads into a fresh volume, so run `docker compose down -v` first if
     the database already exists.
   - Windows without Docker: `.\scripts\start-local.ps1`, then `.\e2e\scripts\seed-local.ps1`
     (safe to re-run).
2. **Install once:** `cd e2e && npm install && npx playwright install chromium`
   (add `firefox webkit` only if you'll run the cross-browser pass).
3. **Run:**

| Command | |
| --- | --- |
| `npm test` | Setup, API and Chromium |
| `npm run test:smoke` | Only the `@smoke` tests |
| `npm run test:api` | API project only |
| `npm run test:all-browsers` | Adds Firefox and WebKit |
| `npm run test:ui` | Playwright UI mode: watch, pick and debug tests |
| `npm run report` | Open the last HTML report, with traces for any failures |

`BASE_URL` (default `http://localhost:4200`) points the suite elsewhere; put it in `e2e/.env`.

`MAILPIT_URL` (default `http://localhost:8025`) is the mail catcher from `docker-compose.yml`.
An application only becomes a client once the link iam-app emails is followed, so every test
that registers a user reads that email from Mailpit. Without Docker, run Mailpit yourself and
point iam-app at it (`SPRING_MAIL_HOST`/`SPRING_MAIL_PORT`); tests that register fail otherwise.

## How the suite stays independent

- **Each worker trades from its own account.** `e2e/seed/e2e_seed.sql` creates
  `e2e.trader.00`–`15@leap.test`, each with $1,000,000. The `trader` fixture picks one by
  Playwright's parallel index, so parallel tests never share balances or positions.
- **Nothing is cleaned up, because it can't be.** Orders, executions and the cash ledger are
  append-only at the database level. Tests assert before/after differences, never absolute
  balances, and are safe to re-run against the same database indefinitely.
- **Shared personas are read-only.** `alice` (seeded demo data), `history` (25 orders for
  pagination) and `multi` (two accounts). Anything that registers, fails logins or needs a known
  position uses a trader or a freshly registered user.
- **State is set up through the API.** A sell test buys its shares with `trader.api.placeOrder`,
  and signing in injects the JWT into `localStorage`. Only the sign-in specs use the form.
- **Failures the backend won't produce on demand** (a crash mid-order, a service outage) are
  simulated with `page.route`.

## Writing tests

- Import `test` / `expect` from `fixtures/test` (or `apiTest` for API-only specs). Choose who is
  signed in with `test.use({ persona: 'trader' | 'alice' | ... })`; the default is signed out.
- Locate by role and label first (`getByRole('button', { name: 'Buy AAPL' })`). Fall back to
  `data-testid` only where there is no accessible handle.
- **Never wait for `networkidle`**, because the live price stream keeps a request open forever.
  Don't use fixed sleeps either. ESLint rejects both.
- **Prices change every second.** Assert formats and relationships, never specific prices.
- Every test fails on uncaught page errors and unexpected `console.error`. A test that makes a
  request fail on purpose lists it with `test.use({ allowedConsoleErrors: [/pattern/] })`.

## Market hours tests

The normal stack runs with market hours off (`docker-compose.e2e.yml` sets `MARKET_HOURS_MODE:
ALWAYS_OPEN`), so market orders fill at any hour. Behaviour outside regular trading - queued market
orders, extended-hours limit orders, stops waiting for the open, frozen and thin prices - needs a
stack pinned to one session, so those specs live in `tests/market-hours/` with a config of their own
(`playwright.market-hours.config.ts`, `npm run test:market-hours`). The main `npm test` skips them.

Each spec file applies to one session and **skips itself on any other**, saying what it needed, so
running against the wrong stack is harmless:

| Spec | Stack pinned to | Covers |
| --- | --- | --- |
| `after-hours.spec.ts` | `AFTER_HOURS` (or `PRE_MARKET`) | Header pill and hours card, wider spread, a market order queued, extended-hours limit fills, a limit without it waits, stops flagged |
| `closed.spec.ts` | `CLOSED`, for a few minutes | Frozen prices with live quotes, queueing with the 5% collar and the cash held, cancelling, the collar affordability check, limit orders waiting |
| `opening.spec.ts` | `CLOSED`, opening within 15 minutes | A queued market order fills when the market opens, and the client is emailed (needs Mailpit) |

Start the stack with the E2E seed and a pinned session, then run the suite:

```bash
# Docker: E2E_MARKET_HOURS_MODE=REAL turns market hours on; the force variables pin the session.
export E2E_MARKET_HOURS_MODE=REAL MARKET_HOURS_FORCE_SESSION=AFTER_HOURS
docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build
cd e2e && npm run test:market-hours

# Then the closed session, which opens after 5 minutes (restart so the countdown starts afresh):
export MARKET_HOURS_FORCE_SESSION=CLOSED MARKET_HOURS_FORCE_MINUTES=5
docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --force-recreate order-app market-data-app
cd e2e && npm run test:market-hours
```

Without Docker: `.\scripts\start-local.ps1 -MarketSession AFTER_HOURS`, then `.\e2e\scripts\seed-local.ps1`
and `npm run test:market-hours`; for the closed run, `-MarketSession CLOSED -MarketOpensInMinutes 5`.
Without Docker there is no Mailpit, so the email check at the end of `opening.spec.ts` fails.

`closed.spec.ts` has to finish inside the minutes the stack is closed for (about a minute of
testing), after which `opening.spec.ts` waits for the open. The two services each count their
minutes from their own start, so they can disagree for a few seconds. Opening gaps are capped at 3%,
inside the 5% collar, so the queued order always fills.

These specs are not part of the Jenkins `E2E` stage.

## CI

The Jenkins `E2E` stage starts the `frontend` service next to the backend and runs the suite in
`e2e/Dockerfile`. That image is Node, the suite's dependencies and Chromium's headless shell,
roughly a third of the size of the official Playwright image. It is tagged by
`package-lock.json`'s hash, so it is built once and reused until dependencies change. It runs with
2 workers, one retry, and a trace (not video) kept only for failures. Results feed the build's
test report, and the HTML report and traces are archived as build artifacts.

## Known app issues found while building the suite

- **The profile menu doesn't close on a click elsewhere on the page.** Its backdrop is
  `position: fixed` inside a header whose `backdrop-filter` makes the header its containing
  block, so the backdrop covers only the header.
- **Symbol search can close under the user.** `onBlur()` schedules a close 150ms later and
  `onFocus()` doesn't cancel it. Typing doesn't reopen the list; only refocusing does.
  `pages/symbol-search.ts` retries around this.
- **Colour contrast** is below WCAG AA for muted text on every page. The submit buttons lose
  their accessible name while loading, and the "Sign up" / "Sign in" footer links are `<a>`
  without `href`, so they can't be reached with the keyboard.
