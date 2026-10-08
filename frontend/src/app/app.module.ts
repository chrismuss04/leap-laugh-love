import { NgModule, inject } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { HTTP_INTERCEPTORS, provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { ReactiveFormsModule, FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { CanActivateFn, Router, RouterModule, Routes } from '@angular/router';

import { AppComponent } from './app.component';
import { SignInComponent } from './sign-in/sign-in.component';
import { CreateAccountComponent } from './create-account/create-account.component';
import { ForgotPasswordComponent } from './forgot-password/forgot-password.component';
import { ResetPasswordComponent } from './reset-password/reset-password.component';
import { OrderHistoryComponent } from './order-history/order-history';
import { HoldingsComponent } from './holdings/holdings';
import { ShellComponent } from './shell/shell';
import { ProfileComponent } from './profile/profile';
import { SettingsComponent } from './settings/settings';
import { AuthService } from './services/auth.service';
import { AuthInterceptor } from './interceptors/auth.interceptor';

// A signed-in user has no use for password recovery, and no sign-in screen to show it on.
const signedOutOnly: CanActivateFn = () => {
  const auth = inject(AuthService);
  return !auth.isAuthenticated() || inject(Router).parseUrl(auth.homeUrl());
};

// Activity Reporting: staff have no access to trading, so its pages send them to reporting. The
// trading APIs refuse staff tokens anyway; this keeps staff from landing on pages that can't load.
export const clientOnly: CanActivateFn = () => {
  const auth = inject(AuthService);
  return !auth.isStaff() || inject(Router).parseUrl(auth.homeUrl());
};

// Activity Reporting: only staff see the reporting dashboard.
export const staffOnly: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isStaff() || inject(Router).parseUrl(auth.homeUrl());
};

// Every signed-in page renders inside the shell, which owns the header and navigation.
export const routes: Routes = [
  // Password recovery. Signed-out visitors get the sign-in screen rather than the router outlet
  // (see app.component.html), and that screen picks its view from the URL - so these render
  // nothing themselves. They exist so the catch-all below doesn't redirect the emailed reset
  // link to the dashboard.
  { path: 'forgot-password', canActivate: [signedOutOnly], children: [] },
  { path: 'reset-password', canActivate: [signedOutOnly], children: [] },
  // Activity Reporting: staff sign in to this instead of the trading shell.
  {
    path: 'reporting',
    canActivate: [staffOnly],
    loadComponent: () => import('./reporting/reporting').then(m => m.ReportingComponent)
  },
  {
    path: '',
    component: ShellComponent,
    canActivate: [clientOnly],
    children: [
      // Lazy: the dashboard is most of the app's code, and sign-in doesn't need it.
      { path: 'dashboard', loadComponent: () => import('./dashboard/dashboard').then(m => m.DashboardComponent) },
      // The same dashboard scoped to one account; the overview above covers all of them.
      { path: 'dashboard/:accountId', loadComponent: () => import('./dashboard/dashboard').then(m => m.DashboardComponent) },
      { path: 'orders', component: OrderHistoryComponent },
      { path: 'holdings', component: HoldingsComponent },
      // Opening accounts and moving cash between them, for all accounts or one; same pills as the dashboard.
      { path: 'accounts', loadComponent: () => import('./accounts/accounts').then(m => m.AccountsComponent) },
      { path: 'accounts/:accountId', loadComponent: () => import('./accounts/accounts').then(m => m.AccountsComponent) },
      { path: 'profile', component: ProfileComponent },
      { path: 'settings', component: SettingsComponent },
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' }
    ]
  },
  // Each role's own home; the guards above would bounce staff from the dashboard anyway.
  { path: '**', redirectTo: () => inject(AuthService).homeUrl() }
];

@NgModule({ declarations: [
        AppComponent,
        SignInComponent,
        CreateAccountComponent
    ],
    bootstrap: [AppComponent], imports: [BrowserModule,
        ReactiveFormsModule,
        FormsModule,
        CommonModule,
        RouterModule.forRoot(routes),
        OrderHistoryComponent,
        HoldingsComponent,
        ForgotPasswordComponent,
        ResetPasswordComponent], providers: [
        AuthService,
        {
            provide: HTTP_INTERCEPTORS,
            useClass: AuthInterceptor,
            multi: true
        },
        provideHttpClient(withInterceptorsFromDi())
    ] })
export class AppModule { }
