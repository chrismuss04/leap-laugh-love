import { Injectable } from '@angular/core';
import { HttpRequest, HttpHandler, HttpEvent, HttpInterceptor, HttpErrorResponse } from '@angular/common/http';
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
    // Session Timeout & Revocation: preserve captured logout credentials and don't attach stale tokens to login.
    const isLogin = request.url === '/api/iam/auth/login';
    const isLogout = request.url === '/api/iam/session/logout';
    if (authToken && !isLogin && !request.headers.has('Authorization')) {
      request = request.clone({
        setHeaders: {
          Authorization: `Bearer ${authToken}`
        }
      });
    }

    // Pass the cloned request to the next handler
    return next.handle(request).pipe(
      catchError((error: HttpErrorResponse) => {
        // Only a request that carried a token means the session expired. Without one - signing
        // in - a 401 is the answer itself (e.g. wrong password), and the caller shows it;
        // reloading here would wipe the form and swallow the message.
        // Session Timeout & Revocation: avoid logout recursion and responses from an older login.
        if (error.status === 401 && authToken && !isLogin && !isLogout) {
          this.authService.expireSession(authToken);
        }

        return throwError(() => error);
      })
    );
  }
}
