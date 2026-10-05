import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService, LoginResponse } from '../services/auth.service';
import { SignInComponent } from './sign-in.component';

describe('SignInComponent', () => {
  let fixture: ComponentFixture<SignInComponent>;
  let component: SignInComponent;
  let auth: jasmine.SpyObj<AuthService>;
  let router: jasmine.SpyObj<Router>;

  const fill = (email: string, password: string, rememberMe = false) =>
    component.signinForm.setValue({ email, password, rememberMe });

  beforeEach(() => {
    localStorage.removeItem('rememberMe');
    auth = jasmine.createSpyObj('AuthService', ['login'], { sessionMessage: signal<string | null>(null) });
    router = jasmine.createSpyObj('Router', ['navigateByUrl']);
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
