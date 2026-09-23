import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { ClientProfile, ProfileService } from '../services/profile';
import { PriceStreamService } from '../services/price-stream';

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
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './shell.html',
  styleUrl: './shell.css'
})
export class ShellComponent implements OnInit, OnDestroy {
  readonly profile = signal<ClientProfile | null>(null);
  readonly menuOpen = signal(false);

  private readonly auth = inject(AuthService);
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

  ngOnInit(): void {
    this.profileService.getMe().subscribe({
      next: profile => this.profile.set(profile),
      // The header still works without a name; the avatar falls back to "?".
      error: () => this.profile.set(null)
    });
  }

  ngOnDestroy(): void {
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
    this.stream.stop();
    this.auth.logout();
  }
}
