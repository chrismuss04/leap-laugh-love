import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ReactiveFormsModule } from '@angular/forms';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, RouterModule } from '@angular/router';
import { SignInComponent } from './sign-in.component';
import { CreateAccountComponent } from '../create-account/create-account.component';
import { ForgotPasswordComponent } from '../forgot-password/forgot-password.component';
import { ResetPasswordComponent } from '../reset-password/reset-password.component';

describe('SignInComponent password recovery', () => {
  let fixture: ComponentFixture<SignInComponent>;
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      declarations: [SignInComponent, CreateAccountComponent],
      imports: [
        ReactiveFormsModule,
        ForgotPasswordComponent,
        ResetPasswordComponent,
        RouterModule.forRoot([
          { path: 'forgot-password', children: [] },
          { path: 'reset-password', children: [] },
          { path: 'dashboard', children: [] },
          { path: '**', redirectTo: 'dashboard' }
        ])
      ],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(SignInComponent);
    fixture.detectChanges();
  });

  const element = () => fixture.nativeElement as HTMLElement;

  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('shows the sign-in form by default', () => {
    expect(element().textContent).toContain('Welcome back');
    expect(element().querySelector('app-forgot-password')).toBeNull();
    expect(element().querySelector('app-reset-password')).toBeNull();
  });

  it('opens the forgot password screen from the link, and returns from it', async () => {
    element().querySelector<HTMLAnchorElement>('a.forgot-password')!.click();
    await settle();

    expect(router.url).toBe('/forgot-password');
    expect(element().querySelector('app-forgot-password')).not.toBeNull();
    expect(element().querySelector('.tabs')).toBeNull();
    expect(element().textContent).not.toContain('Welcome back');

    element().querySelector<HTMLAnchorElement>('app-forgot-password .auth-footer-link a')!.click();
    await settle();

    expect(element().querySelector('app-forgot-password')).toBeNull();
    expect(element().textContent).toContain('Welcome back');
  });

  it('opens the reset screen for the emailed link, keeping its token', async () => {
    await router.navigateByUrl('/reset-password?token=abc123');
    await settle();

    expect(element().querySelector('app-reset-password')).not.toBeNull();
    expect(element().querySelector('app-reset-password form')).not.toBeNull();
    expect(element().querySelector('.tabs')).toBeNull();
  });
});
