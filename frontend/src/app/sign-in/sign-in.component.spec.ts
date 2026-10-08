import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ReactiveFormsModule } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, RouterModule } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { AuthService, LoginResponse } from '../services/auth.service';
import { SignInComponent } from './sign-in.component';
import { CreateAccountComponent } from '../create-account/create-account.component';
import { ForgotPasswordComponent } from '../forgot-password/forgot-password.component';
import { ResetPasswordComponent } from '../reset-password/reset-password.component';

describe('SignInComponent', () => {
  let fixture: ComponentFixture<SignInComponent>;
  let component: SignInComponent;
  let auth: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;

  const fill = (email: string, password: string, rememberMe = false) =>
    component.signinForm.setValue({ email, password, rememberMe });

  beforeEach(() => {
    localStorage.removeItem('rememberMe');
    auth = jasmine.createSpyObj('AuthService', ['login', 'homeUrl'], { sessionMessage: signal<string | null>(null) });
    auth.homeUrl.and.returnValue('/dashboard');
    router = jasmine.createSpyObj('Router', ['navigateByUrl'], { events: new Subject<unknown>(), url: '/' });
    TestBed.configureTestingModule({
      declarations: [SignInComponent],
      imports: [ReactiveFormsModule],
      providers: [{ provide: AuthService, useValue: auth }, { provide: Router, useValue: router }]
    });
    fixture = TestBed.createComponent(SignInComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => localStorage.removeItem('rememberMe'));

  it('refuses an invalid form', () => {
    component.onSignIn();
    expect(auth.login).not.toHaveBeenCalled();
    expect(component.errorMessage()).toBe('Please fill in all required fields correctly.');
  });

  it('signs in and routes to the dashboard', () => {
    auth.login.and.returnValue(of({} as LoginResponse));
    fill('ada@example.com', 'password1');
    component.onSignIn();
    expect(auth.login).toHaveBeenCalledWith('ada@example.com', 'password1');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/dashboard');
    expect(component.isLoading()).toBeFalse();
    expect(localStorage.getItem('rememberMe')).toBeNull();
  });

  // Activity Reporting: staff sign in on the same screen and land on reporting, not trading.
  it('routes staff to the reporting dashboard', () => {
    auth.login.and.returnValue(of({ accessToken: 't', role: 'COMMERCIAL_ANALYST' } as LoginResponse));
    auth.homeUrl.and.returnValue('/reporting');
    fill('analyst@leap.com', 'password1');
    component.onSignIn();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/reporting');
  });

  it('remembers the user when asked', () => {
    auth.login.and.returnValue(of({} as LoginResponse));
    fill('ada@example.com', 'password1', true);
    component.onSignIn();
    expect(localStorage.getItem('rememberMe')).toBe('true');
  });

  it('shows the server message, or a fallback, when sign-in fails', () => {
    fill('ada@example.com', 'password1');
    auth.login.and.returnValue(throwError(() => ({ error: { message: 'Bad credentials' } })));
    component.onSignIn();
    expect(component.errorMessage()).toBe('Bad credentials');
    expect(component.isLoading()).toBeFalse();

    auth.login.and.returnValue(throwError(() => ({})));
    component.onSignIn();
    expect(component.errorMessage()).toBe('Sign in failed. Please try again.');
  });

  it('toggles password visibility', () => {
    component.togglePasswordVisibility();
    expect(component.showPassword).toBeTrue();
  });

  it('explains field errors only once touched', () => {
    expect(component.isFieldInvalid('email')).toBeFalse();
    component.signinForm.get('email')!.markAsTouched();
    expect(component.isFieldInvalid('email')).toBeTrue();
    expect(component.getErrorMessage('email')).toBe('Email is required');

    component.signinForm.get('email')!.setValue('bad');
    expect(component.getErrorMessage('email')).toBe('Please enter a valid email address');

    component.signinForm.get('password')!.setValue('short');
    expect(component.getErrorMessage('password')).toBe('Password must be at least 8 characters');

    component.signinForm.get('rememberMe')!.setErrors({ other: true });
    expect(component.getErrorMessage('rememberMe')).toBe('Invalid input');
    expect(component.getErrorMessage('nope')).toBe('');
    expect(component.isFieldInvalid('nope')).toBeFalse();
  });
});

describe('SignInComponent password recovery', () => {
  let fixture: ComponentFixture<SignInComponent>;
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      declarations: [SignInComponent, CreateAccountComponent],
      imports: [
        ReactiveFormsModule,
        ForgotPasswordComponent,
        ResetPasswordComponent,
        RouterModule.forRoot([
          { path: 'forgot-password', children: [] },
          { path: 'reset-password', children: [] },
          { path: 'dashboard', children: [] },
          { path: '**', redirectTo: 'dashboard' }
        ])
      ],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(SignInComponent);
    fixture.detectChanges();
  });

  const element = () => fixture.nativeElement as HTMLElement;

  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('shows the sign-in form by default', () => {
    expect(element().textContent).toContain('Welcome back');
    expect(element().querySelector('app-forgot-password')).toBeNull();
    expect(element().querySelector('app-reset-password')).toBeNull();
  });

  it('opens the forgot password screen from the link, and returns from it', async () => {
    element().querySelector<HTMLAnchorElement>('a.forgot-password')!.click();
    await settle();

    expect(router.url).toBe('/forgot-password');
    expect(element().querySelector('app-forgot-password')).not.toBeNull();
    expect(element().querySelector('.tabs')).toBeNull();
    expect(element().textContent).not.toContain('Welcome back');

    element().querySelector<HTMLAnchorElement>('app-forgot-password .auth-footer-link a')!.click();
    await settle();

    expect(element().querySelector('app-forgot-password')).toBeNull();
    expect(element().textContent).toContain('Welcome back');
  });

  it('opens the reset screen for the emailed link, keeping its token', async () => {
    await router.navigateByUrl('/reset-password?token=abc123');
    await settle();
    // The reset screen holds its form back until the server has accepted the link's token.
    const check = TestBed.inject(HttpTestingController).expectOne('/api/iam/auth/reset-password/validate');
    expect(check.request.body).toEqual({ token: 'abc123' });
    check.flush(null);
    await settle();

    expect(element().querySelector('app-reset-password')).not.toBeNull();
    expect(element().querySelector('app-reset-password form')).not.toBeNull();
    expect(element().querySelector('.tabs')).toBeNull();
  });
});
