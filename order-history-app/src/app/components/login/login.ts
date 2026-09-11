import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './login.html',
  styleUrl: './login.css'
})
export class LoginComponent {
  email = 'alice.johnson@leap.com';
  password = 'Password123!';
  loading = false;
  error: string | null = null;

  private authService = inject(AuthService);
  private router = inject(Router);

  login() {
    this.loading = true;
    this.error = null;

    this.authService.login(this.email, this.password).subscribe({
      next: () => {
        this.router.navigate(['/orders']);
      },
      error: (err) => {
        this.error = err.error?.message || 'Login failed';
        this.loading = false;
      }
    });
  }
}
