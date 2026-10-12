/** Every seeded user shares this password (seed_iam.sql, e2e/seed/e2e_seed.sql). */
export const PASSWORD = process.env.E2E_PASSWORD ?? 'Password123!';

export interface Persona {
  email: string;
  fullName: string;
  /** Experience label the header shows under the name. */
  experience: string;
  accountNumbers: string[];
}

/**
 * Seeded users with a fixed role in the suite. Personas other than the traders are shared by
 * every worker, so tests must only READ their data - anything that places an order, registers
 * or fails logins uses a trader or a freshly registered user instead.
 */
export const personas = {
  /** Seeded demo user with the richest order history. Read-only. */
  alice: {
    email: 'alice.johnson@leap.com',
    fullName: 'Alice Johnson',
    experience: 'Advanced investor',
    accountNumbers: ['ACC-001-01']
  },
  /** Two funded accounts; used by the account-selector tests (which run serially). */
  multi: {
    email: 'e2e.multi@leap.test',
    fullName: 'E2E Multi Account',
    experience: 'Advanced investor',
    accountNumbers: ['ACC-E2E-M1', 'ACC-E2E-M2']
  },
  /** 25 rejected orders, one per hour back from seeding - enough for two history pages. */
  history: {
    email: 'e2e.history@leap.test',
    fullName: 'E2E History',
    experience: 'Novice investor',
    accountNumbers: ['ACC-E2E-H1']
  }
} satisfies Record<string, Persona>;

export type PersonaName = keyof typeof personas;

/**
 * Seeded staff (seed_iam.sql), who sign in to reporting rather than trading. Read-only: a failed
 * login against one counts toward its lockout, so tests must only sign them in successfully.
 */
export const staff = {
  analyst: { email: 'commercial.analyst@leap.com', roleLabel: 'Commercial analyst', dashboardHeading: 'Trading Activity' },
  tradingOps: { email: 'trading.ops@leap.com', roleLabel: 'Trading operations', dashboardHeading: 'Order Audit' }
} as const;

export const TRADER_COUNT = 16;

/** The funded trader reserved for one Playwright worker, so parallel tests never share an account. */
export function trader(parallelIndex: number): Persona {
  if (parallelIndex >= TRADER_COUNT) {
    throw new Error(`Only ${TRADER_COUNT} E2E traders are seeded; run with --workers=${TRADER_COUNT} or fewer.`);
  }
  const n = String(parallelIndex).padStart(2, '0');
  return {
    email: `e2e.trader.${n}@leap.test`,
    fullName: `E2E Trader ${n}`,
    experience: 'Intermediate investor',
    accountNumbers: [`ACC-E2E-${n}`]
  };
}
