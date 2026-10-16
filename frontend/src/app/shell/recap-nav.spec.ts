import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ShellComponent } from './shell';
import { AuthService } from '../services/auth.service';
import { PriceStreamService } from '../services/price-stream';
import { RecapSeenService, lastRecapMonth } from '../services/recap-seen';

// Monthly recap: the header tab and profile menu item, and their "new" marks.
describe('Recap in the shell', () => {
  let fixture: ComponentFixture<ShellComponent>;
  let http: HttpTestingController;

  function mount(): void {
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: PriceStreamService, useValue: { status: signal('idle'), stop: () => {} } }]
    });
    http = TestBed.inject(HttpTestingController);
    const now = Math.floor(Date.now() / 1000);
    TestBed.inject(AuthService).login('client@example.com', 'password').subscribe();
    http.expectOne('/api/iam/auth/login').flush({
      accessToken: 'header.' + btoa(JSON.stringify({ sid: 'test-session', iat: now, exp: now + 3600 })) + '.signature'
    });
    fixture = TestBed.createComponent(ShellComponent);
    fixture.detectChanges();
    http.expectOne('/api/iam/v1/clients/me').flush({
      clientId: 'client', email: 'client@example.com', fullName: 'Test Client',
      experienceLevel: 'NOVICE', status: 'ACTIVE'
    });
    fixture.componentInstance.menuOpen.set(true);
    fixture.detectChanges();
  }

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    fixture?.destroy();
    http.match(() => true).forEach(request => request.flush(null));
    localStorage.clear();
  });

  const el = (): HTMLElement => fixture.nativeElement;
  const tab = (): HTMLElement => Array.from(el().querySelectorAll<HTMLElement>('.nav-link'))
    .find(link => link.textContent?.includes('Recap'))!;
  const menuItem = (): HTMLElement => Array.from(el().querySelectorAll<HTMLElement>('.menu-item'))
    .find(item => item.textContent?.includes('Monthly recap'))!;

  it('has a wide-screen Recap tab after Accounts and a Monthly recap menu item after Profile', () => {
    mount();

    const links = Array.from(el().querySelectorAll('.nav-link')).map(link => link.textContent?.trim().split(/\s/)[0]);
    expect(links.indexOf('Recap')).toBe(links.indexOf('Accounts') + 1);
    expect(tab().classList).toContain('wide-only');

    const items = Array.from(el().querySelectorAll('.menu-item')).map(item => item.textContent?.trim());
    expect(items[items.findIndex(item => item?.startsWith('Profile')) + 1]).toMatch(/^Monthly recap/);
    expect(menuItem().classList).not.toContain('narrow-only');
  });

  it('marks a recap not yet opened, for sight and screen readers', () => {
    mount();

    expect(tab().classList).toContain('is-new');
    expect(tab().textContent).toContain('(new)');
    expect(menuItem().querySelector('.menu-pill')?.textContent).toBe('New');
  });

  it('drops the marks as soon as the recap is opened', () => {
    mount();

    TestBed.inject(RecapSeenService).markSeen(lastRecapMonth());
    fixture.detectChanges();

    expect(tab().classList).not.toContain('is-new');
    expect(menuItem().querySelector('.menu-pill')).toBeNull();
  });

  it('shows no marks once the newest recap has been seen', () => {
    localStorage.setItem('recap_seen_month', lastRecapMonth());
    mount();

    expect(tab().classList).not.toContain('is-new');
    expect(menuItem().querySelector('.menu-pill')).toBeNull();
  });
});
