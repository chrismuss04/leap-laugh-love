import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, BehaviorSubject } from 'rxjs';
import { tap } from 'rxjs/operators';

/**
 * Interface for login request
 */
export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * Interface for login response
 */
export interface LoginResponse {
  accessToken: string;
  user?: {
    id: string;
    email: string;
    name: string;
  };
}

/**
 * Authentication Service
 * 
 * Handles all authentication-related HTTP calls and state management
 */
@Injectable({
  providedIn: 'root'
})
export class AuthService {
  // Same-origin path: the dev server (see proxy.conf.js) and any deployment reverse proxy
  // forward /api/iam to iam-app, so this works wherever the browser is running.
  private apiUrl = '/api/iam/auth';

  // BehaviorSubject to track authentication state
  private isAuthenticatedSubject = new BehaviorSubject<boolean>(this.hasToken());
  public isAuthenticated$ = this.isAuthenticatedSubject.asObservable();

  // BehaviorSubject to track current user
  private currentUserSubject = new BehaviorSubject<any>(this.getUserFromStorage());
  public currentUser$ = this.currentUserSubject.asObservable();

  constructor(private httpClient: HttpClient) {}

  /**
   * Login user with email and password
   */
  login(email: string, password: string): Observable<LoginResponse> {
    const loginRequest: LoginRequest = { email, password };

    return this.httpClient.post<LoginResponse>(
      `${this.apiUrl}/login`,
      loginRequest
    ).pipe(
      tap(response => {
        // Store token in localStorage
        localStorage.setItem('auth_token', response.accessToken);
        // Store user info, if the backend provided any
        if (response.user) {
          localStorage.setItem('current_user', JSON.stringify(response.user));
        }
        // Update authentication state
        this.isAuthenticatedSubject.next(true);
        this.currentUserSubject.next(response.user ?? null);
      })
    );
  }

  /**
   * Logout user
   */
  logout(): void {
    // Remove token and user from localStorage
    localStorage.removeItem('auth_token');
    localStorage.removeItem('current_user');
    localStorage.removeItem('rememberMe');
    // Update authentication state
    this.isAuthenticatedSubject.next(false);
    this.currentUserSubject.next(null);
  }

  /**
   * Get authentication token
   */
  getToken(): string | null {
    return localStorage.getItem('auth_token');
  }

  /**
   * Check if user is authenticated
   */
  isAuthenticated(): boolean {
    return this.hasToken();
  }

  /**
   * Get current user
   */
  getCurrentUser(): any {
    return this.getUserFromStorage();
  }

  /**
   * Verify token is still valid with backend
   */
  verifyToken(): Observable<any> {
    return this.httpClient.post(`${this.apiUrl}/verify`, {});
  }

  /**
   * Refresh authentication token
   */
  refreshToken(): Observable<LoginResponse> {
    return this.httpClient.post<LoginResponse>(
      `${this.apiUrl}/refresh`,
      {}
    ).pipe(
      tap(response => {
        localStorage.setItem('auth_token', response.accessToken);
        this.isAuthenticatedSubject.next(true);
      })
    );
  }

  /**
   * Private helper: Check if token exists
   */
  private hasToken(): boolean {
    return !!localStorage.getItem('auth_token');
  }

  /**
   * Private helper: Get user from storage
   */
  private getUserFromStorage(): any {
    const userJson = localStorage.getItem('current_user');
    return userJson ? JSON.parse(userJson) : null;
  }
}
