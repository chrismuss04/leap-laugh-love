import { Component, EventEmitter, OnInit, Output } from '@angular/core';
import { AbstractControl, FormBuilder, FormGroup, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { ClientRegistrationService, RegistrationRequest } from '../services/client-registration.service';
import { COUNTRIES, CountryOption } from '../shared/countries';

type ExperienceLabel = 'Beginner' | 'Intermediate' | 'Advanced';

const EXPERIENCE_API_MAP: Record<ExperienceLabel, 'NOVICE' | 'INTERMEDIATE' | 'ADVANCED'> = {
  Beginner: 'NOVICE',
  Intermediate: 'INTERMEDIATE',
  Advanced: 'ADVANCED'
};

function minimumAgeValidator(minAge: number): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    if (!control.value) {
      return null;
    }

    const dob = new Date(control.value);
    const today = new Date();
    let age = today.getFullYear() - dob.getFullYear();
    const monthDiff = today.getMonth() - dob.getMonth();
    if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < dob.getDate())) {
      age--;
    }

    return age >= minAge ? null : { minimumAge: { requiredAge: minAge } };
  };
}

@Component({
  selector: 'app-create-account',
  templateUrl: './create-account.component.html',
  styleUrls: ['./create-account.component.css']
})
export class CreateAccountComponent implements OnInit {
  @Output() switchToSignIn = new EventEmitter<void>();

  registrationForm!: FormGroup;
  isLoading = false;
  errorMessage = '';
  successMessage = '';

  readonly countries: CountryOption[] = COUNTRIES;
  readonly experienceLevels: ExperienceLabel[] = ['Beginner', 'Intermediate', 'Advanced'];

  constructor(
    private formBuilder: FormBuilder,
    private clientRegistrationService: ClientRegistrationService
  ) {}

  ngOnInit(): void {
    this.registrationForm = this.formBuilder.group({
      firstName: ['', Validators.required],
      lastName: ['', Validators.required],
      dateOfBirth: ['', [Validators.required, minimumAgeValidator(21)]],
      email: ['', [Validators.required, Validators.email]],
      phone: ['', [Validators.required, Validators.pattern(/^[0-9+()\-\s]{7,20}$/)]],
      streetAddress: ['', Validators.required],
      city: ['', Validators.required],
      postalCode: ['', Validators.required],
      country: ['', Validators.required],
      ssn: ['', [Validators.required, Validators.pattern(/^\d{3}-\d{2}-\d{4}$/)]],
      experienceLevel: ['', Validators.required],
      agreeToTerms: [false, Validators.requiredTrue]
    });
  }

  /** Auto-formats the SSN input as the user types: 123456789 -> 123-45-6789 */
  onSsnInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const digits = input.value.replace(/\D/g, '').slice(0, 9);

    let formatted = digits;
    if (digits.length > 5) {
      formatted = `${digits.slice(0, 3)}-${digits.slice(3, 5)}-${digits.slice(5)}`;
    } else if (digits.length > 3) {
      formatted = `${digits.slice(0, 3)}-${digits.slice(3)}`;
    }

    this.registrationForm.get('ssn')?.setValue(formatted);
  }

  selectExperience(level: ExperienceLabel): void {
    const control = this.registrationForm.get('experienceLevel');
    control?.setValue(level);
    control?.markAsTouched();
  }

  isExperienceSelected(level: ExperienceLabel): boolean {
    return this.registrationForm.get('experienceLevel')?.value === level;
  }

  onSubmit(): void {
    if (this.registrationForm.invalid) {
      this.registrationForm.markAllAsTouched();
      this.errorMessage = 'Please fill in all required fields correctly.';
      return;
    }

    this.isLoading = true;
    this.errorMessage = '';
    this.successMessage = '';

    const v = this.registrationForm.value;
    const request: RegistrationRequest = {
      email: v.email,
      phone: v.phone,
      fullName: `${v.firstName} ${v.lastName}`.trim(),
      dateOfBirth: v.dateOfBirth,
      ssn: v.ssn,
      addressLine1: v.streetAddress,
      city: v.city,
      postalCode: v.postalCode,
      countryCode: v.country,
      experienceLevel: EXPERIENCE_API_MAP[v.experienceLevel as ExperienceLabel],
      initialDepositAmount: 0
    };

    this.clientRegistrationService.register(request).subscribe({
      next: () => {
        this.isLoading = false;
        this.successMessage = 'Application submitted! Redirecting to sign in...';
        setTimeout(() => this.switchToSignIn.emit(), 1500);
      },
      error: (error) => {
        this.isLoading = false;
        this.errorMessage = error.error?.message || error.error || 'Submission failed. Please try again.';
      }
    });
  }

  isFieldInvalid(fieldName: string): boolean {
    const field = this.registrationForm.get(fieldName);
    return !!(field && field.invalid && (field.dirty || field.touched));
  }

  getErrorMessage(fieldName: string): string {
    const field = this.registrationForm.get(fieldName);
    if (!field || !field.errors) {
      return '';
    }

    if (field.hasError('required')) {
      return 'This field is required';
    }

    if (field.hasError('email')) {
      return 'Please enter a valid email address';
    }

    if (fieldName === 'phone' && field.hasError('pattern')) {
      return 'Please enter a valid phone number';
    }

    if (fieldName === 'ssn' && field.hasError('pattern')) {
      return 'SSN / Tax ID must be in format XXX-XX-XXXX';
    }

    if (field.hasError('minimumAge')) {
      return `You must be at least ${field.errors['minimumAge'].requiredAge} years old`;
    }

    return 'Invalid input';
  }
}
