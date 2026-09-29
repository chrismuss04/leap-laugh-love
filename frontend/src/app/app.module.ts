import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { HTTP_INTERCEPTORS, provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { ReactiveFormsModule, FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { RouterModule, Routes } from '@angular/router';

import { AppComponent } from './app.component';
import { SignInComponent } from './sign-in/sign-in.component';
import { CreateAccountComponent } from './create-account/create-account.component';
import { OrderHistoryComponent } from './order-history/order-history';
import { HoldingsComponent } from './holdings/holdings';
import { ShellComponent } from './shell/shell';
import { AuthService } from './services/auth.service';
import { AuthInterceptor } from './interceptors/auth.interceptor';

// Every signed-in page renders inside the shell, which owns the header and navigation.
const routes: Routes = [
  {
    path: '',
    component: ShellComponent,
    children: [
      // Lazy: the dashboard is most of the app's code, and sign-in doesn't need it.
      { path: 'dashboard', loadComponent: () => import('./dashboard/dashboard').then(m => m.DashboardComponent) },
      { path: 'orders', component: OrderHistoryComponent },
      { path: 'holdings', component: HoldingsComponent },
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
        HoldingsComponent], providers: [
        AuthService,
        {
            provide: HTTP_INTERCEPTORS,
            useClass: AuthInterceptor,
            multi: true
        },
        provideHttpClient(withInterceptorsFromDi())
    ] })
export class AppModule { }
