// Session Timeout & Revocation: fake time tests run the full ten-minute boundary without waiting.
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { BehaviorSubject } from 'rxjs';
import { AuthService } from './auth.service';
import { SessionActivityService } from './session-activity';

describe('SessionActivityService', () => {
  let service: SessionActivityService;
  let http: HttpTestingController;
  let token: string | null;
  let authenticated: BehaviorSubject<boolean>;
  let addListener: jasmine.Spy;
  let reasons: string[];
  const endpoint = '/api/iam/session/activity';
  const idle = 600_000;

  function makeToken(age = 0, lifetime = 3600_000): string {
    const now = Math.floor(Date.now() / 1000) * 1000;
    return 'header.' + btoa(JSON.stringify({
      sid: 'session-1', iat: (now - age) / 1000, exp: (now + lifetime) / 1000
    })) + '.signature';
  }

  // Invoke the installed listener as the browser would; real synthetic DOM events are untrusted.
  function interact(name = 'pointermove'): void {
    const call = addListener.calls.all().find(call => call.args[0] === name);
    expect(call).toBeDefined();
    (call!.args[1] as EventListener)({ isTrusted: true } as Event);
  }

  beforeEach(() => {
    localStorage.clear();
    authenticated = new BehaviorSubject(true);
    token = makeToken();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(),
        { provide: AuthService, useValue: {
          getToken: () => token, isAuthenticated$: authenticated.asObservable()
        } }]
    });
    service = TestBed.inject(SessionActivityService);
    http = TestBed.inject(HttpTestingController);
    addListener = spyOn(document, 'addEventListener').and.callThrough();
    spyOnProperty(document, 'visibilityState', 'get').and.returnValue('visible');
    reasons = [];
    service.expired$.subscribe(reason => reasons.push(reason));
  });

  afterEach(() => {
    service.stop();
    http.verify();
    localStorage.clear();
  });

  it('expires exactly ten minutes after activity without background heartbeats', fakeAsync(() => {
    token = makeToken();
    service.start();
    const fraction = Date.now() % 1000;
    tick(idle - fraction - 1);
    expect(reasons).toEqual([]);
    http.expectNone(endpoint);
    tick(1);
    expect(reasons).toEqual(['inactivity']);
  }));

  for (const event of ['pointermove', 'pointerdown', 'keydown', 'wheel', 'touchstart']) {
    it('reports ' + event + ' and resets the inactivity deadline', fakeAsync(() => {
      service.start();
      tick(300_000);
      interact(event);
      http.expectOne(endpoint).flush(null);
      tick(idle - 1);
      expect(reasons).toEqual([]);
      tick(1);
      expect(reasons).toEqual(['inactivity']);
    }));
  }

  it('throttles noisy events and has no delayed heartbeat', fakeAsync(() => {
    service.start();
    interact();
    http.expectOne(endpoint).flush(null);
    for (let i = 0; i < 10; i++) interact();
    http.expectNone(endpoint);
    tick(1000);
    http.expectNone(endpoint);
    interact();
    http.expectOne(endpoint).flush(null);
    service.stop();
  }));

  it('ignores synthetic DOM events and focus as user activity', fakeAsync(() => {
    service.start();
    document.dispatchEvent(new Event('pointermove'));
    document.dispatchEvent(new Event('visibilitychange'));
    window.dispatchEvent(new Event('focus'));
    http.expectNone(endpoint);
    service.stop();
  }));

  it('checks wall-clock time when a suspended tab resumes', fakeAsync(() => {
    service.start();
    const later = Date.now() + idle;
    spyOn(Date, 'now').and.returnValue(later);
    window.dispatchEvent(new Event('focus'));
    expect(reasons).toEqual(['inactivity']);
    http.expectNone(endpoint);
  }));

  it('accepts shared activity only from the same session', fakeAsync(() => {
    service.start();
    tick(300_000);
    localStorage.setItem('session_activity', JSON.stringify({sessionId: 'session-1', lastActivity: Date.now()}));
    window.dispatchEvent(new StorageEvent('storage', {key: 'session_activity'}));
    tick(300_001);
    expect(reasons).toEqual([]);
    tick(299_999);
    expect(reasons).toEqual(['inactivity']);
  }));

  it('does not extend inactivity after a network failure', fakeAsync(() => {
    token = makeToken();
    service.start();
    const fraction = Date.now() % 1000;
    tick(300_000);
    interact();
    http.expectOne(endpoint).error(new ProgressEvent('error'));
    tick(idle - 300_000 - fraction);
    expect(reasons).toEqual(['inactivity']);
  }));

  it('does not let a slow response extend the interaction timestamp', fakeAsync(() => {
    service.start();
    tick(1000);
    interact();
    const request = http.expectOne(endpoint);
    tick(5000);
    request.flush(null);
    tick(idle - 5000);
    expect(reasons).toEqual(['inactivity']);
  }));

  it('never extends the absolute token expiry', fakeAsync(() => {
    token = makeToken(0, 60_000);
    service.start();
    const fraction = Date.now() % 1000;
    tick(1000);
    interact();
    http.expectOne(endpoint).flush(null);
    tick(59_000 - fraction);
    expect(reasons).toEqual(['expired']);
  }));

  it('stops listeners and pending requests after logout', fakeAsync(() => {
    service.start();
    interact();
    const request = http.expectOne(endpoint);
    authenticated.next(false);
    expect(request.cancelled).toBe(true);
    tick(idle);
    expect(reasons).toEqual([]);
  }));

  it('does not revive an expired session when the user returns', fakeAsync(() => {
    token = makeToken(idle + 1000);
    service.start();
    expect(reasons).toEqual(['inactivity']);
    http.expectNone(endpoint);
  }));
});
