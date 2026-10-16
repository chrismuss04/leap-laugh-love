import { Component, OnInit, signal, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs/operators';
import { AuthService } from '../services/auth.service';

type RecoveryView = 'forgot-password' | 'reset-password' | 'verify-email';

/** The emailed-link screen (password recovery, or confirming a registration) a URL asks for, if any. */
function recoveryViewFor(url: string): RecoveryView | null {
  const path = url.split(/[?#]/)[0];
  if (path === '/forgot-password') { return 'forgot-password'; }
  if (path === '/reset-password') { return 'reset-password'; }
  if (path === '/verify-email') { return 'verify-email'; }
  return null;
}

/** Trading Tools: whether a URL is a Trading Tools page, which a shared link may open straight onto. */
export function isToolsUrl(url: string): boolean {
  return /^\/tools(\/|$)/.test(url.split(/[?#]/)[0]);
}

@Component({
    selector: 'app-sign-in',
    templateUrl: './sign-in.component.html',
    styleUrls: ['./sign-in.component.css'],
    standalone: false
})
export class SignInComponent implements OnInit {
  /**
   * Trading Tools: the tools link this page load opened on, so signing in lands there rather than
   * on the dashboard. Taken once, so whoever signs in after a logout starts at home.
   */
  static deepLink: string | null = isToolsUrl(location.pathname) ? location.pathname + location.search : null;

  // Session Timeout & Revocation: stays reactive if logout confirmation later fails.
  readonly sessionMessage = inject(AuthService).sessionMessage;
  // Form and state variables
  signinForm!: FormGroup;
  // Signals, because the app runs zoneless: a plain field set in an HTTP callback wouldn't
  // re-render until some unrelated event happened to trigger change detection.
  readonly isLoading = signal(false);
  readonly errorMessage = signal('');
  readonly successMessage = signal('');
  showPassword: boolean = false;
  activeTab: 'signin' | 'create-account' = 'signin';
  // Password recovery and email confirmation follow the URL rather than a tab, because an emailed
  // link has to open straight onto its screen.
  readonly recoveryView = toSignal(
    inject(Router).events.pipe(
      filter(event => event instanceof NavigationEnd),
      map(event => recoveryViewFor(event.urlAfterRedirects))
    ),
    { initialValue: recoveryViewFor(inject(Router).url) }
  );

  constructor(
    private formBuilder: FormBuilder,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.initializeForm();
  }

  /**
   * Initialize the sign-in form with validation rules
   */
  private initializeForm(): void {
    this.signinForm = this.formBuilder.group({
      email: ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, Validators.minLength(8)]],
      rememberMe: [false]
    });
  }

  /**
   * Handle sign-in form submission
   */
  onSignIn(): void {
    if (this.signinForm.invalid) {
      this.errorMessage.set('Please fill in all required fields correctly.');
      return;
    }

    this.isLoading.set(true);
    this.errorMessage.set('');
    this.successMessage.set('');

    const { email, password, rememberMe } = this.signinForm.value;

    // Call authentication service
    this.authService.login(email, password).subscribe({
      next: (response) => {
        this.isLoading.set(false);

        // Store token if remember me is checked
        if (rememberMe) {
          localStorage.setItem('rememberMe', 'true');
        }

        // The app swaps to the signed-in shell as soon as the token is stored, so route there in
        // place. A full page load here would re-bootstrap the app and fetch everything twice.
        // Activity Reporting: staff go to the reporting dashboard, clients to trading.
        // Staff Dashboards: signing out leaves the router on the last page, and both staff roles
        // share /reporting - so re-match the route even when the URL hasn't changed, or the next
        // staff member to sign in gets the previous one's dashboard.
        // Trading Tools: a client who opened a tool's link goes to that tool; staff still go home.
        const deepLink = SignInComponent.deepLink;
        SignInComponent.deepLink = null;
        const target = deepLink && !this.authService.isStaff() && this.router.url === deepLink
          ? deepLink : this.authService.homeUrl();
        this.router.navigateByUrl(target, { onSameUrlNavigation: 'reload' });
      },
      error: (error) => {
        this.isLoading.set(false);
        this.errorMessage.set(error.error?.message || 'Sign in failed. Please try again.');
      }
    });
  }

  /**
   * Toggle password visibility
   */
  togglePasswordVisibility(): void {
    this.showPassword = !this.showPassword;
  }

  /**
   * Check if a form field is invalid and touched
   */
  isFieldInvalid(fieldName: string): boolean {
    const field = this.signinForm.get(fieldName);
    return !!(field && field.invalid && (field.dirty || field.touched));
  }

  /**
   * Get error message for a specific field
   */
  getErrorMessage(fieldName: string): string {
    const field = this.signinForm.get(fieldName);
    
    if (!field || !field.errors) {
      return '';
    }

    if (field.hasError('required')) {
      return `${this.capitalize(fieldName)} is required`;
    }
    
    if (field.hasError('email')) {
      return 'Please enter a valid email address';
    }
    
    if (field.hasError('minlength')) {
      return `${this.capitalize(fieldName)} must be at least 8 characters`;
    }

    return 'Invalid input';
  }

  /**
   * Capitalize first letter of string
   */
  private capitalize(str: string): string {
    return str.charAt(0).toUpperCase() + str.slice(1);
  }
}
