import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
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
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
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

    expect(text).toContain('analyst@leap.com');
    expect(text).toContain('Commercial analyst');
    // The trading shell loads the client profile and price stream; staff tokens can't read either.
    http.expectNone(() => true);
    fixture.destroy();
  });

  // Staff Dashboards: each role's dashboard renders inside the shell; see dashboards.spec.ts.
  it('renders the role dashboard in its outlet, under a link home', () => {
    const fixture = TestBed.createComponent(ReportingComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('main router-outlet')).not.toBeNull();
    expect(el.querySelector('a.brand')!.getAttribute('href')).toBe('/reporting');
    fixture.destroy();
  });

  it('tracks session activity while open', () => {
    const activity = TestBed.inject(SessionActivityService);
    vi.spyOn(activity, 'start').mockImplementation(() => {});
    vi.spyOn(activity, 'stop').mockImplementation(() => {});
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
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
    fixture.destroy();
  });
});
