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
const signedOutOnly: CanActivateFn = () =>
  !inject(AuthService).isAuthenticated() || inject(Router).parseUrl('/dashboard');

// Every signed-in page renders inside the shell, which owns the header and navigation.
export const routes: Routes = [
  // Password recovery. Signed-out visitors get the sign-in screen rather than the router outlet
  // (see app.component.html), and that screen picks its view from the URL - so these render
  // nothing themselves. They exist so the catch-all below doesn't redirect the emailed reset
  // link to the dashboard.
  { path: 'forgot-password', canActivate: [signedOutOnly], children: [] },
  { path: 'reset-password', canActivate: [signedOutOnly], children: [] },
  {
    path: '',
    component: ShellComponent,
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
  { path: '**', redirectTo: 'dashboard' }
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
