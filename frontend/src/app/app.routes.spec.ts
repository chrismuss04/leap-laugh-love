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
      expect(page).withContext(`no route for /${path}`).toBeDefined();
      expect(await page!.loadComponent!()).toBe(AccountsComponent);
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
      expect(reachesPage(url)).withContext(`${url} has no route`).toBeTrue();
    }

    fixture.destroy();
    localStorage.clear();
    http.match(() => true).forEach(request => request.flush(null));
  });
});
