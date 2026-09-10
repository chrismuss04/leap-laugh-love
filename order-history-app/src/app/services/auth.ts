import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, BehaviorSubject } from 'rxjs';
import { tap } from 'rxjs/operators';

interface LoginResponse {
  accessToken: string;
  expirationSeconds: number;
}

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly API_URL = 'http://localhost:8081/api/iam';
  private accessTokenSubject = new BehaviorSubject<string | null>(null);
  private clientIdSubject = new BehaviorSubject<string | null>(null);

  accessToken$ = this.accessTokenSubject.asObservable();
  clientId$ = this.clientIdSubject.asObservable();

  constructor(private http: HttpClient) {
    const stored = localStorage.getItem('accessToken');
    if (stored) {
      this.accessTokenSubject.next(stored);
      const clientId = this.extractClientIdFromToken(stored);
      if (clientId) this.clientIdSubject.next(clientId);
    }
  }

  login(email: string, password: string): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>(`${this.API_URL}/auth/login`, { email, password })
      .pipe(
        tap(response => {
          localStorage.setItem('accessToken', response.accessToken);
          this.accessTokenSubject.next(response.accessToken);
          const clientId = this.extractClientIdFromToken(response.accessToken);
          if (clientId) {
            localStorage.setItem('clientId', clientId);
            this.clientIdSubject.next(clientId);
          }
        })
      );
  }

  logout(): void {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('clientId');
    this.accessTokenSubject.next(null);
    this.clientIdSubject.next(null);
  }

  isAuthenticated(): boolean {
    return !!this.accessTokenSubject.value;
  }

  getAccessToken(): string | null {
    return this.accessTokenSubject.value;
  }

  getClientId(): string | null {
    return this.clientIdSubject.value;
  }

  private extractClientIdFromToken(token: string): string | null {
    try {
      const parts = token.split('.');
      if (parts.length !== 3) return null;
      const payload = JSON.parse(atob(parts[1]));
      return payload.sub || null;
    } catch (e) {
      console.error('Error decoding token:', e);
      return null;
    }
  }
}
