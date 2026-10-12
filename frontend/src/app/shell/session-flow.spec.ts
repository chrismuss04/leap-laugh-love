// Session Timeout & Revocation: verify the mounted shell connects inactivity to sign-in state.
import { ComponentFixture, TestBed, fakeAsync, tick, flushMicrotasks } from '@angular/core/testing';
import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ShellComponent } from './shell';
import { AuthService } from '../services/auth.service';
import { PriceStreamService } from '../services/price-stream';
import type { Mock } from 'vitest';

// Reproduce AppComponent's parent bindings: testing the shell alone misses mid-render logout.
@Component({
  imports: [CommonModule, ShellComponent],
  template: `<p *ngIf="!(auth.isAuthenticated$ | async)">Sign in</p>
             <app-shell *ngIf="auth.isAuthenticated$ | async"></app-shell>`
})
class SessionHostComponent {
  readonly auth = inject(AuthService);
}

describe('Session expiry in the signed-in shell', () => {
  let fixture: ComponentFixture<ShellComponent>;
  let auth: AuthService;
  let http: HttpTestingController;
  let stop: Mock;

  beforeEach(async () => {
    localStorage.clear();
    stop = vi.fn().mockName('stop');
    await TestBed.configureTestingModule({
      imports: [ShellComponent, SessionHostComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: PriceStreamService, useValue: { status: signal('idle'), stop } }]
    }).compileComponents();
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    fixture?.destroy();
    http.verify();
    localStorage.clear();
  });

  it('handles a malformed stored token without changing parent bindings during rendering', fakeAsync(() => {
    localStorage.setItem('auth_token', 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.invalid-signature');
    // Simulate a reload: AuthService initializes its authenticated state from the stored token.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SessionHostComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: PriceStreamService, useValue: { status: signal('idle'), stop } }]
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    const host = TestBed.createComponent(SessionHostComponent);
    try {
      expect(() => host.detectChanges()).not.toThrow();
      flushMicrotasks();
      http.expectOne('/api/iam/session/logout').flush(null, {status: 401, statusText: 'Unauthorized'});
      http.match('/api/iam/v1/clients/me').forEach(request => request.flush(null));
      host.detectChanges();
      expect(host.nativeElement.textContent).toContain('Sign in');
      expect(auth.getToken()).toBeNull();
    } finally {
      host.destroy();
    }
  }));

  function mount(): void {
    const now = Math.floor(Date.now() / 1000);
    const token = 'header.' + btoa(JSON.stringify({sid: 'test-session', iat: now, exp: now + 3600})) + '.signature';
    auth.login('client@example.com', 'password').subscribe();
    http.expectOne('/api/iam/auth/login').flush({accessToken: token});
    fixture = TestBed.createComponent(ShellComponent);
    fixture.detectChanges();
    http.expectOne('/api/iam/v1/clients/me').flush({
      clientId: 'client', email: 'client@example.com', fullName: 'Test Client',
      experienceLevel: 'NOVICE', status: 'ACTIVE'
    });
    fixture.detectChanges();
  }

  it('clears authentication and exposes the inactivity message at the deadline', fakeAsync(() => {
    mount();
    let signedIn = true;
    const subscription = auth.isAuthenticated$.subscribe(value => signedIn = value);
    tick(600000 - Date.now() % 1000);
    expect(signedIn).toBe(false);
    expect(auth.getToken()).toBeNull();
    expect(auth.sessionMessage()).toContain('10 minutes of inactivity');
    expect(stop).toHaveBeenCalled();
    http.expectOne('/api/iam/session/logout').flush(null);
    subscription.unsubscribe();
  }));

  it('the sign-out menu action revokes the session and stops the stream', fakeAsync(() => {
    mount();
    fixture.nativeElement.querySelector('.profile-button').click();
    fixture.detectChanges();
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    const logoutButton = buttons.find(button => /sign out|log out|logout/i.test(button.textContent ?? ''));
    expect(logoutButton).toBeDefined();
    logoutButton!.click();
    expect(auth.isAuthenticated()).toBe(false);
    expect(stop).toHaveBeenCalled();
    http.expectOne('/api/iam/session/logout').flush(null);
    tick(600000);
    expect(auth.sessionMessage()).toBe('You have been signed out.');
  }));
});
