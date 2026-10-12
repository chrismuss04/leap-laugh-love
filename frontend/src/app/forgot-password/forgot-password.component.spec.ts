import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ForgotPasswordComponent } from './forgot-password.component';

const URL = '/api/iam/auth/forgot-password';

describe('ForgotPasswordComponent', () => {
  let fixture: ComponentFixture<ForgotPasswordComponent>;
  let component: ForgotPasswordComponent;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ForgotPasswordComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ForgotPasswordComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('does not submit without an email', () => {
    component.onSubmit();
    fixture.detectChanges();

    http.expectNone(URL);
    expect(text()).toContain('Email is required');
  });

  it('does not submit a malformed email', () => {
    component.form.setValue({ email: 'not-an-email' });
    component.onSubmit();
    fixture.detectChanges();

    http.expectNone(URL);
    expect(text()).toContain('Please enter a valid email address');
  });

  it('requests a reset link, then replaces the form with a confirmation', () => {
    component.form.setValue({ email: 'alice@example.com' });
    component.onSubmit();

    const request = http.expectOne(URL);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ email: 'alice@example.com' });
    request.flush(null);
    fixture.detectChanges();

    expect(text()).toContain('If an account exists for alice@example.com');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });

  it('keeps the form and reports a failed request', () => {
    component.form.setValue({ email: 'alice@example.com' });
    component.onSubmit();
    http.expectOne(URL).flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(text()).toContain('We couldn\'t send the reset link. Please try again.');
    expect(fixture.nativeElement.querySelector('form')).not.toBeNull();
    expect(component.isLoading()).toBe(false);
  });
});
