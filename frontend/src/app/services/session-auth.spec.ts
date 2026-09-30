// Session Timeout & Revocation: real auth service and interceptor, with HTTP responses controlled by tests.
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpClient, HTTP_INTERCEPTORS, provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { AuthInterceptor } from '../interceptors/auth.interceptor';

describe('Session logout and authentication integration', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  let client: HttpClient;
  const logout = '/api/iam/session/logout';
  const login = '/api/iam/auth/login';
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({providers: [
      provideHttpClient(withInterceptorsFromDi()), provideHttpClientTesting(),
      {provide: HTTP_INTERCEPTORS, useClass: AuthInterceptor, multi: true}
    ]});
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
  });
  afterEach(() => { http.verify(); localStorage.clear(); });

  function signIn(token = 'current-token'): void {
    auth.login('client@example.com', 'password').subscribe();
    http.expectOne(login).flush({accessToken: token});
  }

  it('sends captured credentials to logout and immediately clears local state', () => {
    signIn();
    localStorage.setItem('session_activity', '{}');
    auth.logout();
    const request = http.expectOne(logout);
    expect(request.request.headers.get('Authorization')).toBe('Bearer current-token');
    expect(auth.getToken()).toBeNull();
    expect(localStorage.getItem('session_activity')).toBeNull();
    request.flush(null);
    expect(auth.sessionMessage()).toBe('You have been signed out.');
  });

  it('a rejected protected request ends the matching session without logout recursion', () => {
    signIn();
    client.get('/api/account/balance').subscribe({error: () => {}});
    http.expectOne('/api/account/balance').flush(null, {status: 401, statusText: 'Unauthorized'});
    http.expectOne(logout).flush(null, {status: 401, statusText: 'Unauthorized'});
    http.expectNone(logout);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.sessionMessage()).toContain('expired');
  });

  it('an old 401 cannot log out a newer session', () => {
    signIn('old-token');
    client.get('/api/account/balance').subscribe({error: () => {}});
    const old = http.expectOne('/api/account/balance');
    signIn('new-token');
    old.flush(null, {status: 401, statusText: 'Unauthorized'});
    http.expectNone(logout);
    expect(auth.getToken()).toBe('new-token');
  });

  it('a delayed logout failure cannot overwrite a new login', () => {
    signIn();
    auth.logout();
    const old = http.expectOne(logout);
    signIn('new-token');
    old.error(new ProgressEvent('error'));
    expect(auth.getToken()).toBe('new-token');
    expect(auth.sessionMessage()).toBe('');
  });

  it('reports when server revocation could not be confirmed', fakeAsync(() => {
    signIn();
    auth.logout();
    const request = http.expectOne(logout);
    tick(10000);
    expect(request.cancelled).toBe(true);
    expect(auth.getToken()).toBeNull();
    expect(auth.sessionMessage()).toContain('could not be confirmed');
  }));

  it('does not attach an existing token to a login attempt or expire it on wrong password', () => {
    signIn();
    auth.login('client@example.com', 'wrong').subscribe({error: () => {}});
    const request = http.expectOne(login);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush(null, {status: 401, statusText: 'Unauthorized'});
    expect(auth.getToken()).toBe('current-token');
    http.expectNone(logout);
  });

  it('shows the inactivity message and stops the authenticated state', () => {
    signIn();
    auth.expireSession('current-token', 'inactivity');
    expect(auth.sessionMessage()).toContain('10 minutes of inactivity');
    expect(auth.isAuthenticated()).toBe(false);
    http.expectOne(logout).flush(null);
  });

  it('reacts to sign-out in another tab without sending another logout request', () => {
    signIn();
    let authenticated = true;
    const subscription = auth.isAuthenticated$.subscribe(value => authenticated = value);
    localStorage.removeItem('auth_token');
    window.dispatchEvent(new StorageEvent('storage', {key: 'auth_token'}));
    expect(authenticated).toBe(false);
    expect(auth.sessionMessage()).toContain('another tab');
    http.expectNone(logout);
    subscription.unsubscribe();
  });
});
