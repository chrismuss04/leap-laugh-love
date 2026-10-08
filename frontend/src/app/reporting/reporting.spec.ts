import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ReportingComponent } from './reporting';
import { AuthService } from '../services/auth.service';
import { SessionActivityService } from '../services/session-activity';

describe('ReportingComponent', () => {
  const now = Math.floor(Date.now() / 1000);
  const token = 'header.' + btoa(JSON.stringify({
    role: 'COMMERCIAL_ANALYST', email: 'analyst@leap.com', sid: 'staff-session', iat: now, exp: now + 3600
  })).replace(/=+$/, '') + '.signature';

  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('auth_token', token);
    TestBed.configureTestingModule({
      imports: [ReportingComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.match(() => true).forEach(request => request.flush(null));
    localStorage.clear();
  });

  it('shows who is signed in, without calling any trading or client API', () => {
    const fixture = TestBed.createComponent(ReportingComponent);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;

    expect(text).toContain('Reporting dashboard');
    expect(text).toContain('analyst@leap.com');
    expect(text).toContain('Commercial analyst');
    // The trading shell loads the client profile and price stream; staff tokens can't read either.
    http.expectNone(() => true);
    fixture.destroy();
  });

  it('shows Trading Operations trade reconstruction instead of the report placeholders', () => {
    localStorage.setItem('auth_token', 'header.' + btoa(JSON.stringify({
      role: 'TRADING_OPERATIONS', email: 'ops@leap.com', sid: 'staff-session', iat: now, exp: now + 3600
    })).replace(/=+$/, '') + '.signature');
    const fixture = TestBed.createComponent(ReportingComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-trade-reconstruction')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.placeholder')).toBeNull();
    // Nothing is looked up (or logged) until a search.
    http.expectNone(() => true);
    fixture.destroy();
  });

  it('shows analysts no trade reconstruction', () => {
    const fixture = TestBed.createComponent(ReportingComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-trade-reconstruction')).toBeNull();
    fixture.destroy();
  });

  it('tracks session activity while open', () => {
    const activity = TestBed.inject(SessionActivityService);
    spyOn(activity, 'start');
    spyOn(activity, 'stop');
    const fixture = TestBed.createComponent(ReportingComponent);
    fixture.detectChanges();
    expect(activity.start).toHaveBeenCalled();
    fixture.destroy();
    expect(activity.stop).toHaveBeenCalled();
  });

  it('signs out and revokes the staff session', () => {
    const fixture = TestBed.createComponent(ReportingComponent);
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.sign-out') as HTMLButtonElement).click();

    const logout = http.expectOne('/api/iam/session/logout');
    expect(logout.request.headers.get('Authorization')).toBe(`Bearer ${token}`);
    expect(TestBed.inject(AuthService).isAuthenticated()).toBeFalse();
    fixture.destroy();
  });
});
