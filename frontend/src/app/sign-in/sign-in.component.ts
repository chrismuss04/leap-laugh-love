import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { AuthService } from '../services/auth.service';

@Component({
  selector: 'app-sign-in',
  templateUrl: './sign-in.component.html',
  styleUrls: ['./sign-in.component.css']
})
export class SignInComponent implements OnInit {
  // Form and state variables
  signinForm!: FormGroup;
  isLoading: boolean = false;
  errorMessage: string = '';
  successMessage: string = '';
  showPassword: boolean = false;
  activeTab: 'signin' | 'create-account' = 'signin';

  constructor(
    private formBuilder: FormBuilder,
    private authService: AuthService
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
      this.errorMessage = 'Please fill in all required fields correctly.';
      return;
    }

    this.isLoading = true;
    this.errorMessage = '';
    this.successMessage = '';

    const { email, password, rememberMe } = this.signinForm.value;

    // Call authentication service
    this.authService.login(email, password).subscribe({
      next: (response) => {
        this.isLoading = false;
        this.successMessage = 'Sign in successful! Redirecting...';
        
        // Store token if remember me is checked
        if (rememberMe) {
          localStorage.setItem('rememberMe', 'true');
        }
        
        // Redirect to dashboard after 1 second
        setTimeout(() => {
          window.location.href = '/dashboard';
        }, 1000);
      },
      error: (error) => {
        this.isLoading = false;
        this.errorMessage = error.error?.message || 'Sign in failed. Please try again.';
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
