import { APIRequestContext, APIResponse, expect } from '@playwright/test';
import { formatPhone, formatSsn, Registration } from '../data/factories';
import { confirmationToken } from './mailbox';

// Response shapes, mirroring frontend/src/app/services/*. Only the fields the suite reads.
export interface AccountSummary {
  accountId: string;
  accountNumber: string;
  status: string;
  tradingEnabled: boolean;
}
export interface Position {
  symbol: string;
  instrumentName: string;
  quantity: number;
  averageCost: number;
}
export interface AccountPositions {
  accountId: string;
  accountNumber: string;
  positions: Position[];
}
export interface Balance {
  accounts: { accountId: string; accountNumber: string; currency: string; balance: number }[];
}
export interface OrderResponse {
  orderId: string;
  symbol: string;
  side: 'BUY' | 'SELL';
  quantity: number;
  status: 'SUBMITTED' | 'OPEN' | 'ACCEPTED' | 'REJECTED' | 'FILLED' | 'CANCELLED' | 'EXPIRED';
  rejectionReason: string | null;
  execution: { fillQuantity: number | null; fillPrice: number | null } | null;
  accountBalanceAfter: number | null;
}
export interface OpenOrderResponse {
  orderId: string;
  symbol: string;
  side: 'BUY' | 'SELL';
  quantity: number;
  orderType: string;
  status: string;
  parentOrderId?: string | null;
  ocoGroupId?: string | null;
  /** Whether a limit order may also fill in pre-market and after-hours. */
  extendedHours?: boolean;
  /** Whether this is a market order queued for the open. */
  queued?: boolean;
  /** For a queued market order: the most a buy pays, or the least a sell takes. */
  collarPrice?: number | null;
}
export type MarketSession = 'PRE_MARKET' | 'REGULAR' | 'AFTER_HOURS' | 'CLOSED';
/** GET /api/marketdata/market/status: the session in force and when regular trading next opens. */
export interface MarketStatus {
  session: MarketSession;
  now: string;
  nextSession: MarketSession | null;
  nextTransitionAt: string | null;
  nextRegularOpen: string | null;
  mode: 'REAL' | 'ALWAYS_OPEN';
  forced: boolean;
}
export interface OrderHistoryPage {
  content: { orderId: string; symbol: string; side: string; quantity: number; status: string; submittedAt: string; completedAt?: string }[];
  totalElements: number;
  totalPages: number;
}
export interface LatestQuote {
  symbol: string;
  bidPrice: number;
  askPrice: number;
  lastPrice: number;
  quoteTimestamp: string;
}

/**
 * Typed calls to the backend, used to arrange state (log in, buy a position to sell) and to
 * verify it independently of the UI. Every path goes through the frontend's dev-server proxy,
 * the same route the browser takes, so BASE_URL is the only backend address the suite needs
 * (registering also reads the applicant's email; see mailbox.ts).
 */
export class Api {
  constructor(
    private readonly request: APIRequestContext,
    private readonly token?: string
  ) {}

  /** The same client, authenticated as whoever `token` belongs to. */
  as(token: string): Api {
    return new Api(this.request, token);
  }

  private headers(): Record<string, string> {
    return this.token ? { Authorization: `Bearer ${this.token}` } : {};
  }

  // ---- Raw calls: return the response, for tests that assert on status codes ----

  get(path: string, params?: Record<string, string | number>): Promise<APIResponse> {
    return this.request.get(path, { headers: this.headers(), params });
  }

  post(path: string, data?: unknown): Promise<APIResponse> {
    return this.request.post(path, { headers: this.headers(), data });
  }

  send(method: 'GET' | 'POST', path: string): Promise<APIResponse> {
    return this.request.fetch(path, { method, headers: this.headers(), data: method === 'POST' ? {} : undefined });
  }

  // ---- IAM ----

  loginRaw(email: string, password: string): Promise<APIResponse> {
    return this.request.post('/api/iam/auth/login', { data: { email, password } });
  }

  async login(email: string, password: string): Promise<string> {
    const response = await this.loginRaw(email, password);
    await expectOk(response, `log in as ${email}`);
    return (await response.json()).accessToken;
  }

  registerRaw(r: Registration): Promise<APIResponse> {
    return this.request.post('/api/iam/v1/clients/register', {
      data: {
        email: r.email,
        phone: formatPhone(r.phoneDigits),
        fullName: `${r.firstName} ${r.lastName}`,
        dateOfBirth: r.dateOfBirth,
        ssn: formatSsn(r.ssnDigits),
        addressLine1: r.streetAddress,
        city: r.city,
        stateRegion: r.stateRegion,
        postalCode: r.postalCode,
        countryCode: r.countryCode,
        experienceLevel: { Beginner: 'NOVICE', Intermediate: 'INTERMEDIATE', Advanced: 'ADVANCED' }[r.experience],
        initialDepositAmount: r.initialDeposit,
        password: r.password
      }
    });
  }

  verifyRegistrationRaw(token: string): Promise<APIResponse> {
    return this.request.post('/api/iam/v1/clients/register/verify', { data: { token } });
  }

  /** Applies, then confirms the email with the link iam-app sent, which is what opens the client. */
  async register(r: Registration): Promise<void> {
    await expectOk(await this.registerRaw(r), `register ${r.email}`);
    const token = await confirmationToken(this.request, r.email);
    await expectOk(await this.verifyRegistrationRaw(token), `confirm the email of ${r.email}`);
  }

  // ---- Account ----

  async accounts(): Promise<AccountSummary[]> {
    return this.json(await this.get('/api/account/accounts'), 'list accounts');
  }

  async positions(accountId: string): Promise<AccountPositions> {
    return this.json(await this.get(`/api/account/accounts/${accountId}/positions`), 'list positions');
  }

  async balance(): Promise<Balance> {
    return this.json(await this.get('/api/account/balance'), 'get balance');
  }

  /** Shares of `symbol` held in one account (0 if none). */
  async sharesHeld(accountId: string, symbol: string): Promise<number> {
    const { positions } = await this.positions(accountId);
    return positions.find(p => p.symbol === symbol)?.quantity ?? 0;
  }

  async buyingPower(accountId: string): Promise<number> {
    const { accounts } = await this.balance();
    return accounts.find(a => a.accountId === accountId)?.balance ?? 0;
  }

  // ---- Orders ----

  placeOrderRaw(order: { accountId: string; symbol: string; side: 'BUY' | 'SELL'; quantity: number } & Record<string, unknown>): Promise<APIResponse> {
    return this.post('/api/order/orders', order);
  }

  /** Places an order and requires it to fill - for arranging positions, not for testing orders. */
  async placeOrder(order: { accountId: string; symbol: string; side: 'BUY' | 'SELL'; quantity: number }): Promise<OrderResponse> {
    const result: OrderResponse = await this.json(await this.placeOrderRaw(order), `place ${order.side} ${order.symbol}`);
    expect(result.status, `order ${order.side} ${order.quantity} ${order.symbol}: ${result.rejectionReason}`).toBe('FILLED');
    return result;
  }

  /** The client's open orders (limit, stop and trailing orders waiting for their price). */
  async openOrders(): Promise<OpenOrderResponse[]> {
    return this.json(await this.get('/api/order/orders/open'), 'get open orders');
  }

  /** Cancels every open order, so one test's held cash and shares don't leak into the next. */
  async cancelAllOpenOrders(): Promise<void> {
    for (const order of await this.openOrders()) {
      await this.post(`/api/order/orders/${order.orderId}/cancel`);
    }
  }

  async templates(): Promise<{ templateId: string; name: string }[]> {
    return this.json(await this.get('/api/order/templates'), 'get order templates');
  }

  async deleteAllTemplates(): Promise<void> {
    for (const template of await this.templates()) {
      await this.request.delete(`/api/order/templates/${template.templateId}`, { headers: this.headers() });
    }
  }

  async orderHistory(page = 0, size = 20): Promise<OrderHistoryPage> {
    return this.json(await this.get('/api/order/orders/history', { page, size }), 'get order history');
  }

  // ---- Market data ----

  /** The market session now, and when regular trading next opens. */
  async marketStatus(): Promise<MarketStatus> {
    return this.json(await this.get('/api/marketdata/market/status'), 'get the market status');
  }

  /** The latest ingested quote: what order-app prices an order against. */
  async latestQuote(symbol: string): Promise<LatestQuote> {
    return this.json(await this.get(`/api/marketdata/quotes/${encodeURIComponent(symbol)}`), `quote ${symbol}`);
  }

  private async json<T>(response: APIResponse, what: string): Promise<T> {
    await expectOk(response, what);
    return response.json();
  }
}

async function expectOk(response: APIResponse, what: string): Promise<void> {
  if (!response.ok()) {
    throw new Error(`Could not ${what}: ${response.status()} ${response.url()}\n${await response.text()}`);
  }
}
