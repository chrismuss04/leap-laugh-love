import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { ResetPasswordComponent } from './reset-password.component';

const URL = '/api/iam/auth/reset-password';
const CHECK_URL = '/api/iam/auth/reset-password/validate';

describe('ResetPasswordComponent', () => {
  let fixture: ComponentFixture<ResetPasswordComponent>;
  let component: ResetPasswordComponent;
  let http: HttpTestingController;

  /** Opens the page the way the emailed link does, with the server accepting the link's token. */
  async function open(url: string): Promise<void> {
    await arrive(url);
    if (component.token) {
      http.expectOne(CHECK_URL).flush(null);
      fixture.detectChanges();
    }
  }

  /** Opens the page and leaves the link check unanswered. */
  async function arrive(url: string): Promise<void> {
    TestBed.configureTestingModule({
      imports: [ResetPasswordComponent],
      providers: [
        provideRouter([{ path: 'reset-password', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    http = TestBed.inject(HttpTestingController);
    await TestBed.inject(Router).navigateByUrl(url);
    fixture = TestBed.createComponent(ResetPasswordComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('offers a new link instead of the form when the link has no token', async () => {
    await open('/reset-password');

    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(text()).toContain('This password reset link is invalid or incomplete.');
    expect(text()).toContain('Request a new link');
  });

  it('does not check a link that has no token', async () => {
    await open('/reset-password');

    http.expectNone(CHECK_URL);
  });

  it('checks the link\'s token on arrival and holds the form back until it is accepted', async () => {
    await arrive('/reset-password?token=abc123');

    const check = http.expectOne(CHECK_URL);
    expect(check.request.method).toBe('POST');
    expect(check.request.body).toEqual({ token: 'abc123' });
    expect(fixture.nativeElement.querySelector('form')).toBeNull();

    check.flush(null);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('form')).not.toBeNull();
  });

  it('turns away a used, replaced or expired link instead of showing the form', async () => {
    await arrive('/reset-password?token=abc123');
    http.expectOne(CHECK_URL).flush(
      { error: 'INVALID_RESET_TOKEN', message: 'This password reset link is invalid or has expired.' },
      { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(text()).toContain('This password reset link has expired or has already been used.');
    expect(text()).toContain('Request a new link');

    component.form.setValue({ password: 'NewPassword123', confirmPassword: 'NewPassword123' });
    component.onSubmit();
    http.expectNone(URL);
  });

  it('still shows the form when the link could not be checked', async () => {
    await arrive('/reset-password?token=abc123');
    http.expectOne(CHECK_URL).flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('form')).not.toBeNull();
    expect(text()).not.toContain('has already been used');
  });

  it('does not submit a short password or one that does not match its confirmation', async () => {
    await open('/reset-password?token=abc123');

    component.form.setValue({ password: 'short', confirmPassword: 'other' });
    component.onSubmit();
    fixture.detectChanges();

    http.expectNone(URL);
    expect(text()).toContain('Password must be at least 8 characters');
    expect(text()).toContain('Passwords do not match');
  });

  it('does not submit without a confirmation', async () => {
    await open('/reset-password?token=abc123');

    component.form.setValue({ password: 'NewPassword123', confirmPassword: '' });
    component.onSubmit();
    fixture.detectChanges();

    http.expectNone(URL);
    expect(text()).toContain('Please confirm your new password');
  });

  it('sends the link\'s token with the new password, then confirms', async () => {
    await open('/reset-password?token=abc123');

    component.form.setValue({ password: 'NewPassword123', confirmPassword: 'NewPassword123' });
    component.onSubmit();

    const request = http.expectOne(URL);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ token: 'abc123', newPassword: 'NewPassword123' });
    request.flush(null);
    fixture.detectChanges();

    expect(text()).toContain('Your password has been reset.');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });

  it('shows the server\'s reason when the reset is rejected', async () => {
    await open('/reset-password?token=abc123');

    component.form.setValue({ password: 'NewPassword123', confirmPassword: 'NewPassword123' });
    component.onSubmit();
    http.expectOne(URL).flush({ message: 'Reset link has expired' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(text()).toContain('Reset link has expired');
    expect(fixture.nativeElement.querySelector('form')).not.toBeNull();
  });

  it('falls back to a generic message when the server gives no reason', async () => {
    await open('/reset-password?token=abc123');

    component.form.setValue({ password: 'NewPassword123', confirmPassword: 'NewPassword123' });
    component.onSubmit();
    http.expectOne(URL).flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(text()).toContain('We couldn\'t reset your password.');
  });
});
