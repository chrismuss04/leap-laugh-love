import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../services/auth.service';

/** Asks for the account's email and requests a password reset link for it. */
@Component({
    selector: 'app-forgot-password',
    imports: [CommonModule, ReactiveFormsModule, RouterLink],
    templateUrl: './forgot-password.component.html',
    styles: `
    form { display: flex; flex-direction: column; gap: 18px; }`
})
export class ForgotPasswordComponent {
  private readonly authService = inject(AuthService);

  readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]]
  });

  readonly isLoading = signal(false);
  readonly errorMessage = signal('');
  /** The address the link was requested for, once the request has gone through. */
  readonly sentTo = signal('');

  onSubmit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');

    const { email } = this.form.getRawValue();
    this.authService.requestPasswordReset(email).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.sentTo.set(email);
      },
      error: () => {
        this.isLoading.set(false);
        this.errorMessage.set('We couldn\'t send the reset link. Please try again.');
      }
    });
  }

  isEmailInvalid(): boolean {
    const email = this.form.controls.email;
    return email.invalid && (email.dirty || email.touched);
  }

  getEmailError(): string {
    return this.form.controls.email.hasError('required')
      ? 'Email is required'
      : 'Please enter a valid email address';
  }
}
