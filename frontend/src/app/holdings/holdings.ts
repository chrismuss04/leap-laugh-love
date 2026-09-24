import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '../services/auth.service';
import { HoldingsService, AccountHoldings, ClientHoldings } from '../services/holdings';

@Component({
    selector: 'app-holdings',
    imports: [CommonModule],
    templateUrl: './holdings.html',
    styleUrl: './holdings.css'
})
export class HoldingsComponent implements OnInit {
  accounts = signal<AccountHoldings[]>([]);
  loading = signal(false);
  error = signal<string | null>(null);

  private authService = inject(AuthService);
  private holdingsService = inject(HoldingsService);

  ngOnInit() {
    if (!this.authService.isAuthenticated()) {
      return;
    }
    this.loadHoldings();
  }

  loadHoldings() {
    this.loading.set(true);
    this.error.set(null);

    this.holdingsService.getHoldings().subscribe({
      next: (response: ClientHoldings) => {
        this.accounts.set(response.accounts);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err.error?.message || 'Failed to load holdings');
        this.loading.set(false);
      }
    });
  }

  logout() {
    this.authService.logout();
  }
}
