import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ClientRegistrationService } from '../services/client-registration.service';

/** Where the emailed registration link lands: confirms the email and opens the account. */
@Component({
    selector: 'app-verify-email',
    imports: [CommonModule, RouterLink],
    templateUrl: './verify-email.component.html'
})
export class VerifyEmailComponent {
  /** Proves the visitor received the email; without it there is nothing to confirm. */
  readonly token = inject(ActivatedRoute).snapshot.queryParamMap.get('token');

  /**
   * 'rejected' means the link itself is dead: it was already used, a newer application replaced
   * it, it expired, or its details were registered in the meantime. 'failed' means the check
   * couldn't be made, so the same link is worth trying again.
   */
  readonly state = signal<'confirming' | 'confirmed' | 'rejected' | 'failed'>('confirming');

  constructor() {
    if (!this.token) {
      return;
    }
    inject(ClientRegistrationService).verify(this.token).subscribe({
      next: () => this.state.set('confirmed'),
      error: (error) => this.state.set(error.status === 400 ? 'rejected' : 'failed')
    });
  }
}
