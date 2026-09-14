import { Routes } from '@angular/router';
import { LoginComponent } from './login/login';
import { OrderHistoryComponent } from './order-history/order-history';

export const routes: Routes = [
  { path: '', redirectTo: '/orders', pathMatch: 'full' },
  { path: 'login', component: LoginComponent },
  { path: 'orders', component: OrderHistoryComponent },
  { path: '**', redirectTo: '/orders' }
];
