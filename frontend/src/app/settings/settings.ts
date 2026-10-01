import { Component, effect, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormBuilder, ReactiveFormsModule, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { ShellComponent } from '../shell/shell';
import { ProfileService } from '../services/profile';
import { COUNTRIES } from '../shared/countries';
import { formatPhone } from '../shared/format';

const SAVE_ERRORS: Record<number, string> = {
  400: 'Some details are invalid. Please check them and try again.',
  403: 'Your current password is incorrect.',
  409: 'That email is already registered to another account.'
};

/**
 * Lets the signed-in client change their name, email, phone, address, password and notification preferences.
 * These sit in a collapsible "Account information" section so further settings sections can sit alongside it.
 * Starts from the profile the shell already loaded, and hands the saved one back to it so the
 * header and profile page update too.
 */
@Component({
    selector: 'app-settings',
    imports: [CommonModule, ReactiveFormsModule],
    templateUrl: './settings.html',
    styles: `
    @import '../shared/page-shell.css';
    .page-container { max-width: 640px; }
    form, fieldset { display: flex; flex-direction: column; gap: 16px; }
    form[hidden] { display: none; }
    fieldset { border: 0; padding: 0; margin: 0 0 16px; }
    legend { font-weight: 600; color: var(--foreground); margin-bottom: 12px; }
    .row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    @media (max-width: 640px) { .row { grid-template-columns: 1fr; } }
    .btn-primary { width: auto; align-self: flex-start; padding: 12px 24px; }
    .settings-section { border: 1px solid var(--border); border-radius: var(--radius); margin-bottom: 16px; }
    .section-toggle { display: flex; justify-content: space-between; align-items: center; width: 100%;
      padding: 16px 20px; background: none; border: 0; color: var(--foreground); font: inherit; font-weight: 600;
      cursor: pointer; }
    .chevron { transition: transform 0.2s ease; }
    .chevron.open { transform: rotate(180deg); }
    .settings-section form { padding: 4px 20px 20px; }
    .password-input-wrapper { position: relative; display: flex; align-items: center; }
    .password-input-wrapper .form-input { padding-right: 40px; }
    .password-toggle { position: absolute; right: 10px; display: flex; padding: 0; background: none; border: 0;
      color: var(--muted-foreground); cursor: pointer; }
    .password-toggle:hover { color: var(--foreground); }`
})
export class SettingsComponent {
  readonly shell = inject(ShellComponent);
  private readonly profileService = inject(ProfileService);
  readonly countries = COUNTRIES;

  readonly accountOpen = signal(false);
  readonly showCurrentPassword = signal(false);
  readonly showNewPassword = signal(false);

  readonly saving = signal(false);
  readonly saved = signal(false);
  readonly error = signal<string | null>(null);

  /** A new password must be confirmed, and changing the email or password needs the current one. */
  private readonly credentialsValidator: ValidatorFn = group => {
    const { email, currentPassword, password, confirmPassword } = group.value;
    const errors: ValidationErrors = {};
    if (password !== confirmPassword) errors['passwordsMismatch'] = true;
    if ((password || email !== this.shell.profile()?.email) && !currentPassword) errors['currentPasswordRequired'] = true;
    return Object.keys(errors).length ? errors : null;
  };

  readonly form = inject(FormBuilder).nonNullable.group({
    fullName: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    phone: ['', [Validators.required, Validators.pattern(/^\(\d{3}\) \d{3}-\d{4}$/)]],
    addressLine1: ['', Validators.required],
    addressLine2: '',
    city: ['', Validators.required],
    stateRegion: ['', Validators.required],
    postalCode: ['', Validators.required],
    countryCode: ['', Validators.required],
    notifyOrderFills: true,
    notifyPriceAlerts: true,
    currentPassword: '',
    password: ['', Validators.minLength(8)],
    confirmPassword: ''
  }, { validators: this.credentialsValidator });

  constructor() {
    // Load the profile once it arrives, and again after each save to clear the password fields.
    effect(() => {
      const profile = this.shell.profile();
      if (profile) {
        this.form.reset({ ...profile, phone: formatPhone(profile.phone ?? ''),
          addressLine2: profile.addressLine2 ?? '', stateRegion: profile.stateRegion ?? '' });
      }
    });
  }

  formatPhoneInput(): void {
    this.form.controls.phone.setValue(formatPhone(this.form.controls.phone.value));
  }

  showError(name: keyof typeof this.form.controls, groupError?: string): boolean {
    const control = this.form.controls[name];
    return control.touched && (control.invalid || (!!groupError && this.form.hasError(groupError)));
  }

  save(): void {
    this.form.markAllAsTouched();
    this.saved.set(false);
    this.error.set(null);
    if (this.form.invalid) return;

    const { currentPassword, password, confirmPassword, ...settings } = this.form.getRawValue();
    this.saving.set(true);
    this.profileService.updateMe({
      ...settings,
      addressLine2: settings.addressLine2 || null,
      currentPassword: currentPassword || null,
      newPassword: password || null
    }).pipe(finalize(() => this.saving.set(false))).subscribe({
      next: profile => {
        this.shell.profile.set(profile);
        this.saved.set(true);
      },
      error: (error: HttpErrorResponse) =>
        this.error.set(SAVE_ERRORS[error.status] ?? "Couldn't save your changes. Please try again.")
    });
  }
}
