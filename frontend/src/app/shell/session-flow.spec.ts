// Session Timeout & Revocation: verify the mounted shell connects inactivity to sign-in state.
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ShellComponent } from './shell';
import { AuthService } from '../services/auth.service';
import { PriceStreamService } from '../services/price-stream';

describe('Session expiry in the signed-in shell', () => {
  let fixture: ComponentFixture<ShellComponent>;
  let auth: AuthService;
  let http: HttpTestingController;
  let stop: jasmine.Spy;

  beforeEach(async () => {
    localStorage.clear();
    stop = jasmine.createSpy('stop');
    await TestBed.configureTestingModule({
      imports: [ShellComponent],
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
