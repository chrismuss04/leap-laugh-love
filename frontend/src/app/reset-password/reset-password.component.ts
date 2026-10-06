import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuthService } from '../services/auth.service';

function passwordsMatch(group: AbstractControl): ValidationErrors | null {
  const { password, confirmPassword } = group.value;
  return !confirmPassword || password === confirmPassword ? null : { passwordsMismatch: true };
}

/** Where the emailed reset link lands: sets a new password using the token in the link. */
@Component({
    selector: 'app-reset-password',
    imports: [CommonModule, ReactiveFormsModule, RouterLink],
    templateUrl: './reset-password.component.html',
    styles: `
    form { display: flex; flex-direction: column; gap: 18px; }
    .field-hint { font-size: 12px; color: var(--muted-foreground); margin-top: 6px; line-height: 1.5; }`
})
export class ResetPasswordComponent {
  private readonly authService = inject(AuthService);
  /** Proves the visitor received the email; without it there is nothing to submit. */
  readonly token = inject(ActivatedRoute).snapshot.queryParamMap.get('token');

  readonly form = inject(FormBuilder).nonNullable.group({
    password: ['', [Validators.required, Validators.minLength(8)]],
    confirmPassword: ['', Validators.required]
  }, { validators: passwordsMatch });

  /**
   * Whether the link can still be used: it stops working once it has reset the password, a newer
   * link has replaced it, or it has expired. Checked on arrival so a dead link is turned away
   * before the visitor types a new password.
   */
  readonly linkState = signal<'checking' | 'usable' | 'rejected'>('checking');
  readonly isLoading = signal(false);
  readonly errorMessage = signal('');
  readonly done = signal(false);
  readonly showPassword = signal(false);
  readonly showConfirmPassword = signal(false);

  constructor() {
    if (!this.token) {
      return;
    }
    this.authService.checkResetToken(this.token).subscribe({
      next: () => this.linkState.set('usable'),
      // Only a 400 means the link itself is dead. If the check couldn't be made, show the form:
      // submitting it validates the link again.
      error: (error) => this.linkState.set(error.status === 400 ? 'rejected' : 'usable')
    });
  }

  onSubmit(): void {
    if (!this.token || this.linkState() !== 'usable') {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');

    this.authService.resetPassword(this.token, this.form.getRawValue().password).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.done.set(true);
      },
      error: (error) => {
        this.isLoading.set(false);
        this.errorMessage.set(error.error?.message
          || 'We couldn\'t reset your password. The link may have expired - please request a new one.');
      }
    });
  }

  isFieldInvalid(fieldName: 'password' | 'confirmPassword'): boolean {
    const field = this.form.controls[fieldName];
    if (!field.dirty && !field.touched) {
      return false;
    }
    return field.invalid || (fieldName === 'confirmPassword' && this.form.hasError('passwordsMismatch'));
  }

  getErrorMessage(fieldName: 'password' | 'confirmPassword'): string {
    const field = this.form.controls[fieldName];

    if (field.hasError('required')) {
      return fieldName === 'password' ? 'Password is required' : 'Please confirm your new password';
    }

    if (field.hasError('minlength')) {
      return `Password must be at least ${field.errors?.['minlength'].requiredLength} characters`;
    }

    return 'Passwords do not match';
  }
}
