import { Component, input, output } from '@angular/core';

/**
 * The eye button inside a password field that shows or hides what was typed. The parent owns
 * the visibility; this only reports clicks. Sits in a .password-input-wrapper beside the input.
 */
@Component({
  selector: 'app-password-toggle',
  template: `
    <button
      type="button"
      class="password-toggle"
      (click)="toggle.emit()"
      [attr.aria-label]="visible() ? 'Hide password' : 'Show password'"
      tabindex="-1"
    >
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7Z" stroke="currentColor" stroke-width="1.5" />
        <circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="1.5" />
      </svg>
    </button>
  `
})
export class PasswordToggleComponent {
  /** Whether the password is currently shown. */
  readonly visible = input(false);
  readonly toggle = output<void>();
}
