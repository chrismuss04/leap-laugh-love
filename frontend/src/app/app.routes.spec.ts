import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { By } from '@angular/platform-browser';
import { Route, Router, RouterLink, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { routes } from './app.module';
import { AccountsComponent } from './accounts/accounts';
import { ShellComponent } from './shell/shell';
import { AuthService } from './services/auth.service';
import { PriceStreamService } from './services/price-stream';

/** The pages that render inside the shell, i.e. every route a signed-in link can lead to. */
const pages: Route[] = routes.find(route => route.component === ShellComponent)?.children ?? [];

/** Whether a URL like /accounts/a1 reaches a real page, rather than falling through to the catch-all. */
function reachesPage(url: string): boolean {
  const segments = url.split(/[?#]/)[0].split('/').filter(Boolean);
  return pages.some(page => {
    if (page.redirectTo !== undefined || page.path === '**' || page.path === undefined) {
      return false;
    }
    const pattern = page.path.split('/').filter(Boolean);
    return pattern.length === segments.length
      && pattern.every((part, i) => part.startsWith(':') || part === segments[i]);
  });
}

describe('App routes', () => {
  // Regression: a revert merge once dropped these, and every Accounts link silently led to the dashboard.
  for (const path of ['accounts', 'accounts/:accountId']) {
    it(`/${path} opens the Accounts page`, async () => {
      const page = pages.find(route => route.path === path);
      expect(page, `no route for /${path}`).toBeDefined();
      expect(await page!.loadComponent!()).toBe(AccountsComponent);
    });
  }

  // Without a route of its own, the emailed reset link falls through to the catch-all and lands on the dashboard.
  for (const path of ['forgot-password', 'reset-password']) {
    it(`/${path} is not swallowed by the catch-all`, () => {
      const index = routes.findIndex(route => route.path === path);
      expect(index, `no route for /${path}`).toBeGreaterThanOrEqual(0);
      expect(index).toBeLessThan(routes.findIndex(route => route.path === '**'));
    });
  }

  it('every link in the header and profile menu reaches a real page', () => {
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: PriceStreamService, useValue: { status: signal('idle'), stop: () => {} } }]
    });
    const http = TestBed.inject(HttpTestingController);
    const now = Math.floor(Date.now() / 1000);
    TestBed.inject(AuthService).login('client@example.com', 'password').subscribe();
    http.expectOne('/api/iam/auth/login').flush({
      accessToken: 'header.' + btoa(JSON.stringify({ sid: 'test-session', iat: now, exp: now + 3600 })) + '.signature'
    });

    const fixture = TestBed.createComponent(ShellComponent);
    fixture.detectChanges();
    http.expectOne('/api/iam/v1/clients/me').flush({
      clientId: 'client', email: 'client@example.com', fullName: 'Test Client',
      experienceLevel: 'NOVICE', status: 'ACTIVE'
    });
    fixture.componentInstance.menuOpen.set(true);
    fixture.detectChanges();

    const router = TestBed.inject(Router);
    const urls = fixture.debugElement.queryAll(By.directive(RouterLink))
      .map(link => link.injector.get(RouterLink).urlTree)
      .filter(tree => tree !== null)
      .map(tree => router.serializeUrl(tree!));

    expect(urls).toContain('/accounts');
    for (const url of urls) {
      expect(reachesPage(url), `${url} has no route`).toBe(true);
    }

    fixture.destroy();
    localStorage.clear();
    http.match(() => true).forEach(request => request.flush(null));
  });
});

// Activity Reporting: staff only ever see reporting, and clients only ever see trading.
describe('Role-based routing', () => {
  const signInAs = (role?: string) => {
    const claims = role ? { role, sid: 's', email: 'user@leap.com' } : { sid: 's' };
    localStorage.setItem('auth_token', 'header.' + btoa(JSON.stringify(claims)).replace(/=+$/, '') + '.signature');
  };

  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()]
    });
    router = TestBed.inject(Router);
  });

  afterEach(() => localStorage.clear());

  for (const role of ['COMMERCIAL_ANALYST', 'TRADING_OPERATIONS']) {
    describe(`as ${role}`, () => {
      beforeEach(() => signInAs(role));

      it('opens the reporting dashboard', async () => {
        await router.navigateByUrl('/reporting');
        expect(router.url).toBe('/reporting');
      });

      for (const url of ['/dashboard', '/orders', '/holdings', '/accounts', '/profile', '/settings', '/', '/nowhere']) {
        it(`is sent from ${url} to reporting`, async () => {
          await router.navigateByUrl(url);
          expect(router.url).toBe('/reporting');
        });
      }

      it('is sent from password recovery to reporting', async () => {
        await router.navigateByUrl('/forgot-password');
        expect(router.url).toBe('/reporting');
      });
    });
  }

  // Staff Dashboards: both staff roles share /reporting; only trading operations trace orders.
  describe('staff dashboards', () => {
    const orderUrl = '/reporting/orders/3f2b8c1e-9a4d-4c2e-8f1a-6b7d5e0c9a21';

    it('opens an order lifecycle trace for trading operations', async () => {
      signInAs('TRADING_OPERATIONS');
      await router.navigateByUrl(orderUrl);
      expect(router.url).toBe(orderUrl);
    });

    it('sends a commercial analyst from an order lifecycle trace to reporting', async () => {
      signInAs('COMMERCIAL_ANALYST');
      await router.navigateByUrl(orderUrl);
      expect(router.url).toBe('/reporting');
    });

    for (const role of ['COMMERCIAL_ANALYST', 'TRADING_OPERATIONS']) {
      it(`sends ${role} from an unknown reporting page to reporting`, async () => {
        signInAs(role);
        await router.navigateByUrl('/reporting/nowhere');
        expect(router.url).toBe('/reporting');
      });
    }

    it('sends a client from an order lifecycle trace to the dashboard', async () => {
      signInAs('CLIENT');
      await router.navigateByUrl(orderUrl);
      expect(router.url).toBe('/dashboard');
    });

    it('each role downloads only its own dashboard', () => {
      const reporting = routes.find(route => route.path === 'reporting')!;
      const dashboards = reporting.children!.filter(route => route.path === '' && route.loadComponent);
      expect(dashboards.length).toBe(2);
      for (const route of dashboards) {
        expect(route.canMatch?.length, 'a staff dashboard without a role check').toBe(1);
      }
    });
  });

  describe('as a client', () => {
    beforeEach(() => signInAs('CLIENT'));

    it('is sent from reporting to the dashboard', async () => {
      await router.navigateByUrl('/reporting');
      expect(router.url).toBe('/dashboard');
    });

    it('still opens trading pages', async () => {
      await router.navigateByUrl('/orders');
      expect(router.url).toBe('/orders');
    });

    it('lands on the dashboard from an unknown URL', async () => {
      await router.navigateByUrl('/nowhere');
      expect(router.url).toBe('/dashboard');
    });
  });

  it('treats a token without a role claim as a client', async () => {
    signInAs();
    await router.navigateByUrl('/reporting');
    expect(router.url).toBe('/dashboard');
  });
});

// Activity Reporting regression: signing in as staff while the (hidden) trading shell was already
// activated rendered the dashboard for a moment, firing every trading API with a staff token.
describe('ShellComponent for staff', () => {
  afterEach(() => localStorage.clear());

  it('renders nothing and calls no API', () => {
    localStorage.setItem('auth_token', 'header.' + btoa(JSON.stringify({
      role: 'COMMERCIAL_ANALYST', sid: 's', iat: 1, exp: 9999999999
    })).replace(/=+$/, '') + '.signature');
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: PriceStreamService, useValue: { status: signal('idle'), stop: () => {} } }]
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ShellComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.shell')).toBeNull();
    http.expectNone(() => true);
    fixture.destroy();
  });
});
