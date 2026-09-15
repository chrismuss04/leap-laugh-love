import { Injectable } from '@angular/core';
import {
  HttpRequest,
  HttpHandler,
  HttpEvent,
  HttpInterceptor,
  HttpErrorResponse
} from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuthService } from '../services/auth.service';

/**
 * HTTP Interceptor for handling authentication
 * 
 * This interceptor:
 * 1. Adds the JWT token to every outgoing HTTP request
 * 2. Handles 401 errors by redirecting to login
 * 3. Logs network errors for debugging
 */
@Injectable()
export class AuthInterceptor implements HttpInterceptor {
  constructor(private authService: AuthService) {}

  intercept(
    request: HttpRequest<any>,
    next: HttpHandler
  ): Observable<HttpEvent<any>> {
    // Get the auth token from the service
    const authToken = this.authService.getToken();

    // Clone the request and add authorization header if token exists
    if (authToken) {
      request = request.clone({
        setHeaders: {
          Authorization: `Bearer ${authToken}`
        }
      });
    }

    // Pass the cloned request to the next handler
    return next.handle(request).pipe(
      catchError((error: HttpErrorResponse) => {
        if (error.status === 401) {
          // Token expired or invalid - redirect to login
          this.authService.logout();
          window.location.href = '/';
        }

        return throwError(() => error);
      })
    );
  }
}
