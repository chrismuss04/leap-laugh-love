import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ReactiveFormsModule } from '@angular/forms';
import { of, throwError } from 'rxjs';
import { ClientRegistrationService } from '../services/client-registration.service';
import { CreateAccountComponent } from './create-account.component';

describe('CreateAccountComponent', () => {
  let fixture: ComponentFixture<CreateAccountComponent>;
  let component: CreateAccountComponent;
  let registration: jasmine.SpyObj<ClientRegistrationService>;

  const valid = {
    firstName: 'Ada', lastName: 'Lovelace', dateOfBirth: '1990-01-01', email: 'ada@example.com',
    phone: '(555) 123-4567', password: 'password1', confirmPassword: 'password1', streetAddress: '1 Main St',
    addressLine2: '', city: 'London', stateRegion: 'LDN', postalCode: 'N1', country: 'GB', ssn: '123-45-6789',
    experienceLevel: 'Intermediate', initialDepositAmount: 5000, agreeToTerms: true
  };

  const inputEvent = (value: string) => ({ target: { value } }) as unknown as Event;

  beforeEach(() => {
    registration = jasmine.createSpyObj('ClientRegistrationService', ['register']);
    TestBed.configureTestingModule({
      declarations: [CreateAccountComponent],
      imports: [ReactiveFormsModule],
      providers: [{ provide: ClientRegistrationService, useValue: registration }]
    });
    fixture = TestBed.createComponent(CreateAccountComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('starts invalid', () => {
    expect(component.registrationForm.valid).toBeFalse();
  });

  it('toggles password visibility', () => {
    component.togglePasswordVisibility();
    component.toggleConfirmPasswordVisibility();
    expect(component.showPassword).toBeTrue();
    expect(component.showConfirmPassword).toBeTrue();
  });

  it('formats the SSN as it is typed', () => {
    const ssn = () => component.registrationForm.get('ssn')!.value;
    component.onSsnInput(inputEvent('12'));
    expect(ssn()).toBe('12');
    component.onSsnInput(inputEvent('1234'));
    expect(ssn()).toBe('123-4');
    component.onSsnInput(inputEvent('1234567890123'));
    expect(ssn()).toBe('123-45-6789');
  });

  it('formats the phone number as it is typed', () => {
    component.onPhoneInput(inputEvent('5551234567'));
    expect(component.registrationForm.get('phone')!.value).toBe('(555) 123-4567');
  });

  it('selects an experience level and marks it touched', () => {
    component.selectExperience('Advanced');
    expect(component.isExperienceSelected('Advanced')).toBeTrue();
    expect(component.isExperienceSelected('Beginner')).toBeFalse();
    expect(component.registrationForm.get('experienceLevel')!.touched).toBeTrue();
  });

  describe('validation messages', () => {
    const message = (field: string) => component.getErrorMessage(field);
    const set = (field: string, value: unknown) => {
      component.registrationForm.get(field)!.setValue(value);
      component.registrationForm.get(field)!.markAsTouched();
    };

    it('is empty for a valid or unknown field', () => {
      set('city', 'London');
      expect(message('city')).toBe('');
      expect(message('nope')).toBe('');
    });

    it('explains each rule', () => {
      expect(message('firstName')).toBe('This field is required');
      set('email', 'bad');
      expect(message('email')).toBe('Please enter a valid email address');
      set('phone', '555');
      expect(message('phone')).toBe('Please enter a valid phone number');
      set('ssn', '123');
      expect(message('ssn')).toBe('SSN / Tax ID must be in format XXX-XX-XXXX');
      set('dateOfBirth', '2999-01-01');
      expect(message('dateOfBirth')).toBe('You must be at least 21 years old');
      set('initialDepositAmount', 10);
      expect(message('initialDepositAmount')).toBe('Minimum initial deposit is $5,000');
      set('password', 'short');
      expect(message('password')).toBe('Password must be at least 8 characters');
      set('postalCode', ' ');
      expect(message('agreeToTerms')).toBe('This field is required');
    });

    it('falls back to a generic message for other errors', () => {
      set('city', 'x');
      component.registrationForm.get('city')!.setErrors({ other: true });
      expect(message('city')).toBe('Invalid input');
    });

    it('reports mismatched passwords on the confirmation field', () => {
      set('password', 'password1');
      set('confirmPassword', 'different');
      expect(message('confirmPassword')).toBe('Passwords do not match');
      expect(component.isFieldInvalid('confirmPassword')).toBeTrue();
      set('confirmPassword', 'password1');
      expect(component.isFieldInvalid('confirmPassword')).toBeFalse();
    });

    it('flags only touched or dirty invalid fields', () => {
      expect(component.isFieldInvalid('city')).toBeFalse();
      component.registrationForm.get('city')!.markAsTouched();
      expect(component.isFieldInvalid('city')).toBeTrue();
      expect(component.isFieldInvalid('nope')).toBeFalse();
    });
  });

  describe('submitting', () => {
    it('refuses an incomplete form', () => {
      component.onSubmit();
      expect(registration.register).not.toHaveBeenCalled();
      expect(component.errorMessage()).toBe('Please fill in all required fields correctly.');
    });

    it('sends the application, points the applicant to their email and clears the form', () => {
      registration.register.and.returnValue(of({ message: 'Check your email' }));
      component.registrationForm.setValue(valid);
      component.onSubmit();

      expect(registration.register).toHaveBeenCalledWith(jasmine.objectContaining({
        fullName: 'Ada Lovelace', addressLine1: '1 Main St', addressLine2: undefined, countryCode: 'GB',
        experienceLevel: 'INTERMEDIATE', initialDepositAmount: 5000
      }));
      expect(component.isLoading()).toBeFalse();
      expect(component.successMessage()).toContain(`We've emailed ${valid.email}`);
      expect(component.registrationForm.get('ssn')!.value).toBeNull();
      expect(component.registrationForm.get('password')!.value).toBeNull();
    });

    it('shows the server message, then a plain-text error, then a fallback', () => {
      component.registrationForm.setValue(valid);
      registration.register.and.returnValue(throwError(() => ({ error: { message: 'Email taken' } })));
      component.onSubmit();
      expect(component.errorMessage()).toBe('Email taken');
      expect(component.isLoading()).toBeFalse();

      registration.register.and.returnValue(throwError(() => ({ error: 'Plain text' })));
      component.onSubmit();
      expect(component.errorMessage()).toBe('Plain text');

      registration.register.and.returnValue(throwError(() => ({})));
      component.onSubmit();
      expect(component.errorMessage()).toBe('Submission failed. Please try again.');
    });
  });
});
