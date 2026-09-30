import { Injectable, OnDestroy, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, BehaviorSubject } from 'rxjs';
import { tap, timeout } from 'rxjs/operators';

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
export class AuthService implements OnDestroy {
  // Session Timeout & Revocation: the sign-in screen reads this without a full page reload.
  readonly sessionMessage = signal('');
  private sessionGeneration = 0;
  private readonly onStorage = (event: StorageEvent) => {
    if (event.key !== 'auth_token' && event.key !== null) return;
    if (!this.getToken()) {
      this.sessionGeneration++;
      this.sessionMessage.set('You have been signed out in another tab. Please sign in again.');
      this.isAuthenticatedSubject.next(false);
      this.currentUserSubject.next(null);
    } else {
      // A different login was established in another tab; discard the previous page's state.
      window.location.reload();
    }
  };
  // Same-origin path: the dev server (see proxy.conf.js) and any deployment reverse proxy
  // forward /api/iam to iam-app, so this works wherever the browser is running.
  private apiUrl = '/api/iam/auth';

  // BehaviorSubject to track authentication state
  private isAuthenticatedSubject = new BehaviorSubject<boolean>(this.hasToken());
  public isAuthenticated$ = this.isAuthenticatedSubject.asObservable();

  // BehaviorSubject to track current user
  private currentUserSubject = new BehaviorSubject<any>(this.getUserFromStorage());
  public currentUser$ = this.currentUserSubject.asObservable();

  // Session Timeout & Revocation: localStorage changes are delivered to the other tabs.
  constructor(private httpClient: HttpClient) {
    window.addEventListener('storage', this.onStorage);
  }

  ngOnDestroy(): void {
    window.removeEventListener('storage', this.onStorage);
  }

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
        // Session Timeout & Revocation: an old logout response must not affect a new login.
        this.sessionGeneration++;
        this.sessionMessage.set('');
        localStorage.removeItem('session_activity');
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
    // Session Timeout & Revocation: dispatch revocation with the captured token before clearing it.
    this.endSession('You have been signed out.');
  }

  expireSession(expectedToken: string | null, reason: 'inactivity' | 'expired' = 'expired'): void {
    if (!expectedToken || this.getToken() !== expectedToken) return;
    this.endSession(reason === 'inactivity'
      ? 'Your session expired after 10 minutes of inactivity. Please sign in again.'
      : 'Your session has expired or is no longer valid. Please sign in again.');
  }

  private endSession(message: string): void {
    const token = this.getToken();
    const generation = ++this.sessionGeneration;
    this.sessionMessage.set(message);
    if (token) {
      this.httpClient.post<void>('/api/iam/session/logout', {}, {
        headers: { Authorization: `Bearer ${token}` }
      }).pipe(timeout(10000)).subscribe({
        error: error => {
          // 401 means this session is already unusable. Other failures don't prove revocation.
          if (error.status !== 401 && this.sessionGeneration === generation && !this.getToken()) {
            this.sessionMessage.set('You are signed out on this device, but server logout could not be confirmed.');
          }
        }
      });
    }
    // Remove token and user from localStorage
    localStorage.removeItem('auth_token');
    localStorage.removeItem('current_user');
    localStorage.removeItem('rememberMe');
    localStorage.removeItem('session_activity');
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
