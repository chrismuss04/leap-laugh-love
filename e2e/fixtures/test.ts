import { test as base, expect, request as playwrightRequest, APIRequestContext } from '@playwright/test';
import { Api } from './api';
import { PASSWORD, Persona, PersonaName, personas, trader as traderFor } from '../data/users';

export { expect };

/** The funded account reserved for the current worker. */
export interface Trader {
  persona: Persona;
  token: string;
  accountId: string;
  accountNumber: string;
  /** API client authenticated as this trader. */
  api: Api;
}

interface Options {
  /**
   * Who the page is signed in as. `null` (the default) is signed out; `'trader'` is this worker's
   * own funded account - use it for anything that places orders.
   */
  persona: PersonaName | 'trader' | null;
  /** Console errors a test expects, e.g. from routes it deliberately fails. */
  allowedConsoleErrors: RegExp[];
}

interface ApiFixtures {
  /** Unauthenticated API client; `.as(token)` for an authenticated one. */
  api: Api;
  trader: Trader;
  /** Logs in through the API (cached per worker) and returns the JWT. */
  tokenFor: (email: string) => Promise<string>;
}

interface PageFixtures {
  consoleGuard: void;
}

interface WorkerFixtures {
  workerRequest: APIRequestContext;
  workerTokens: Map<string, string>;
  workerTrader: Trader;
}

// Browsers log every non-2xx response to the console on their own, and the app turns those into
// on-screen messages; only errors the app itself raises are worth failing a test over.
// Firefox also reports web fonts (Google Fonts) that a navigation cancelled mid-download.
const ALWAYS_ALLOWED = [/Failed to load resource/i, /the server responded with a status of/i, /downloadable font: download failed/i];

/** For specs that only call the API: no browser is started. */
export const apiTest = base.extend<ApiFixtures, WorkerFixtures>({
  workerRequest: [
    async ({}, use, workerInfo) => {
      const context = await playwrightRequest.newContext({ baseURL: workerInfo.project.use.baseURL });
      await use(context);
      await context.dispose();
    },
    { scope: 'worker' }
  ],

  // Tokens last 60 minutes, far longer than a worker lives, so each persona logs in once.
  workerTokens: [async ({}, use) => use(new Map()), { scope: 'worker' }],

  workerTrader: [
    async ({ workerRequest, workerTokens }, use, workerInfo) => {
      const persona = traderFor(workerInfo.parallelIndex);
      const api = new Api(workerRequest);
      const token = await api.login(persona.email, PASSWORD);
      workerTokens.set(persona.email, token);
      const accounts = await api.as(token).accounts();
      const account = accounts.find(a => a.accountNumber === persona.accountNumbers[0]);
      if (!account) {
        throw new Error(`${persona.email} has no account ${persona.accountNumbers[0]} - is e2e/seed/e2e_seed.sql loaded?`);
      }
      await use({ persona, token, accountId: account.accountId, accountNumber: account.accountNumber, api: api.as(token) });
    },
    { scope: 'worker' }
  ],

  tokenFor: async ({ workerRequest, workerTokens }, use) => {
    await use(async (email: string) => {
      let token = workerTokens.get(email);
      if (!token) {
        token = await new Api(workerRequest).login(email, PASSWORD);
        workerTokens.set(email, token);
      }
      return token;
    });
  },

  trader: async ({ workerTrader }, use) => use(workerTrader),

  api: async ({ request }, use) => use(new Api(request))
});

/** For UI specs: adds sign-in by persona and fails tests on page errors. */
export const test = apiTest.extend<Options & PageFixtures>({
  persona: [null, { option: true }],
  allowedConsoleErrors: [[], { option: true }],

  // Signing in is just a JWT in localStorage (AuthService), so injecting one skips the login
  // form - which has its own specs - and its 1-second redirect for every other test.
  storageState: async ({ persona, baseURL, tokenFor, workerTrader }, use) => {
    if (persona === null) {
      await use({ cookies: [], origins: [] });
      return;
    }
    const token = persona === 'trader' ? workerTrader.token : await tokenFor(personas[persona].email);
    await use({
      cookies: [],
      origins: [{ origin: new URL(baseURL!).origin, localStorage: [{ name: 'auth_token', value: token }] }]
    });
  },

  consoleGuard: [
    async ({ page, allowedConsoleErrors }, use, testInfo) => {
      const errors: string[] = [];
      const allowed = [...ALWAYS_ALLOWED, ...allowedConsoleErrors];
      page.on('pageerror', error => errors.push(`Uncaught: ${error.message}`));
      page.on('console', message => {
        if (message.type() === 'error' && !allowed.some(re => re.test(message.text()))) {
          errors.push(`console.error: ${message.text()}`);
        }
      });
      await use();
      // Don't pile a second failure onto a test that already failed.
      if (testInfo.status === testInfo.expectedStatus) {
        expect(errors, 'the page raised errors - see the list below').toEqual([]);
      }
    },
    { auto: true }
  ]
});

/** Resolves an account number to its id for a signed-in persona. */
export async function accountId(api: Api, accountNumber: string): Promise<string> {
  const account = (await api.accounts()).find(a => a.accountNumber === accountNumber);
  if (!account) {
    throw new Error(`No account ${accountNumber}`);
  }
  return account.accountId;
}
