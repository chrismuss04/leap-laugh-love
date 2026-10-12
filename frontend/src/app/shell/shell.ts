import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { ClientProfile, ProfileService } from '../services/profile';
import { PriceStreamService } from '../services/price-stream';
// Session Timeout & Revocation: track activity only while the signed-in shell is mounted.
import { SessionActivityService } from '../services/session-activity';
// Session Timeout & Revocation
import { Subscription, asapScheduler, observeOn } from 'rxjs';

const EXPERIENCE_LABELS: Record<ClientProfile['experienceLevel'], string> = {
  NOVICE: 'Novice investor',
  INTERMEDIATE: 'Intermediate investor',
  ADVANCED: 'Advanced investor'
};

/**
 * Layout for every signed-in page: brand, navigation, live-data status and the signed-in client.
 * Pages render into its router outlet, so they share one header instead of each drawing its own.
 */
@Component({
    selector: 'app-shell',
    imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
    templateUrl: './shell.html',
    styleUrl: './shell.css'
})
export class ShellComponent implements OnInit, OnDestroy {
  readonly profile = signal<ClientProfile | null>(null);
  readonly profileLoading = signal(true);
  readonly profileError = signal(false);
  readonly menuOpen = signal(false);

  private readonly auth = inject(AuthService);
  // Session Timeout & Revocation
  private readonly sessionActivity = inject(SessionActivityService);
  private expirySubscription?: Subscription;
  private readonly profileService = inject(ProfileService);
  readonly stream = inject(PriceStreamService);

  readonly initials = computed(() => {
    const name = this.profile()?.fullName ?? this.profile()?.email ?? '';
    return name.split(/\s+/).filter(Boolean).slice(0, 2).map(part => part[0].toUpperCase()).join('') || '?';
  });

  readonly experienceLabel = computed(() => {
    const level = this.profile()?.experienceLevel;
    return level ? EXPERIENCE_LABELS[level] : '';
  });

  readonly statusLabel = computed(() => {
    switch (this.stream.status()) {
      case 'live': return 'Live';
      case 'connecting': return 'Connecting…';
      case 'reconnecting': return 'Reconnecting…';
      default: return 'Offline';
    }
  });

  // Activity Reporting: the route guard keeps staff out, but a shell activated while signed out
  // is still mounted, hidden, when someone signs in - and the sign-in redirect lands only after it
  // renders. Staff tokens can't load any of its data, so it renders and loads nothing for them.
  readonly staff = this.auth.isStaff();

  ngOnInit(): void {
    if (this.staff) return;
    // Session Timeout & Revocation
    const token = this.auth.getToken();
    // Session Timeout & Revocation: invalid tokens can expire synchronously during ngOnInit.
    // Defer the parent authentication update until Angular finishes its current render.
    this.expirySubscription = this.sessionActivity.expired$.pipe(observeOn(asapScheduler)).subscribe(reason => {
      this.stream.stop();
      this.auth.expireSession(token, reason);
    });
    this.sessionActivity.start();
    if (!this.auth.isAuthenticated()) return;
    this.profileService.getMe().subscribe({
      next: profile => {
        this.profile.set(profile);
        this.profileLoading.set(false);
      },
      // Stop the skeleton so the header shows the error; the avatar falls back to "?".
      error: () => {
        this.profileError.set(true);
        this.profileLoading.set(false);
      }
    });
  }

  ngOnDestroy(): void {
    // Session Timeout & Revocation
    this.sessionActivity.stop();
    this.expirySubscription?.unsubscribe();
    this.stream.stop();
  }

  toggleMenu(): void {
    this.menuOpen.update(open => !open);
  }

  closeMenu(): void {
    this.menuOpen.set(false);
  }

  logout(): void {
    this.closeMenu();
    // Session Timeout & Revocation: stop activity before asking the server to revoke the session.
    this.sessionActivity.stop();
    this.stream.stop();
    this.auth.logout();
  }
}
