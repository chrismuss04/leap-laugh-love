import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { AuthService, Role } from '../services/auth.service';
// Session Timeout & Revocation: staff sessions have the same idle timeout as clients'.
import { SessionActivityService } from '../services/session-activity';
import { Subscription, asapScheduler, observeOn } from 'rxjs';
import { ActivityReportComponent } from './activity-report';

const ROLE_LABELS: Record<Role, string> = {
  CLIENT: 'Client',
  TRADING_OPERATIONS: 'Trading operations',
  COMMERCIAL_ANALYST: 'Commercial analyst'
};

/**
 * Activity Reporting: the staff dashboard. It has its own layout rather than the trading shell,
 * which loads the client profile and live prices - neither of which a staff token can read.
 */
@Component({
    selector: 'app-reporting',
    imports: [ActivityReportComponent],
    templateUrl: './reporting.html',
    styleUrl: './reporting.css'
})
export class ReportingComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly sessionActivity = inject(SessionActivityService);
  private expirySubscription?: Subscription;

  readonly email = this.auth.getEmail();
  readonly roleLabel = ROLE_LABELS[this.auth.getRole() ?? 'CLIENT'];
  // Activity reports are for commercial analysts; order-app refuses Trading Operations.
  readonly isAnalyst = this.auth.getRole() === 'COMMERCIAL_ANALYST';

  ngOnInit(): void {
    const token = this.auth.getToken();
    // Defer the parent authentication update until Angular finishes its current render.
    this.expirySubscription = this.sessionActivity.expired$.pipe(observeOn(asapScheduler))
      .subscribe(reason => this.auth.expireSession(token, reason));
    this.sessionActivity.start();
  }

  ngOnDestroy(): void {
    this.sessionActivity.stop();
    this.expirySubscription?.unsubscribe();
  }

  logout(): void {
    // Stop activity before asking the server to revoke the session.
    this.sessionActivity.stop();
    this.auth.logout();
  }
}
