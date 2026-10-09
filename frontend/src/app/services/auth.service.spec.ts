import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService, LoginResponse } from './auth.service';
import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;
  const apiUrl = '/api/iam/auth';

  beforeEach(() => {
    TestBed.configureTestingModule({
    imports: [],
    providers: [AuthService, provideHttpClient(withInterceptorsFromDi()), provideHttpClientTesting()]
});

    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);

    // Clear localStorage before each test
    localStorage.clear();
  });

  afterEach(() => {
    // Session Timeout & Revocation: old logout tests also exercise the new revocation request.
    httpMock.match('/api/iam/session/logout').forEach(request => request.flush(null));
    httpMock.verify();
    localStorage.clear();
  });

  // ==================== Service Initialization ====================
  describe('Service Initialization', () => {
    it('should be created', () => {
      expect(service).toBeTruthy();
    });

    it('should have correct API URL', () => {
      expect(service['apiUrl']).toBe(apiUrl);
    });
  });

  // ==================== Login ====================
  describe('Login', () => {
    it('should send login request with correct credentials', () => {
      const mockEmail = 'test@example.com';
      const mockPassword = 'ValidPassword123';
      const mockResponse: LoginResponse = {
        accessToken: 'test-token',
        user: { id: '1', email: mockEmail, name: 'Test User' }
      };

      service.login(mockEmail, mockPassword).subscribe(response => {
        expect(response).toEqual(mockResponse);
      });

      const req = httpMock.expectOne(`${apiUrl}/login`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ email: mockEmail, password: mockPassword });
      req.flush(mockResponse);
    });

    it('should store token in localStorage after successful login', () => {
      const mockEmail = 'test@example.com';
      const mockPassword = 'ValidPassword123';
      const mockResponse: LoginResponse = {
        accessToken: 'test-token-123',
        user: { id: '1', email: mockEmail, name: 'Test User' }
      };

      service.login(mockEmail, mockPassword).subscribe();

      const req = httpMock.expectOne(`${apiUrl}/login`);
      req.flush(mockResponse);

      expect(localStorage.getItem('auth_token')).toBe('test-token-123');
    });

    it('should store user info in localStorage after successful login', () => {
      const mockEmail = 'test@example.com';
      const mockPassword = 'ValidPassword123';
      const mockUser = { id: '1', email: mockEmail, name: 'Test User' };
      const mockResponse: LoginResponse = {
        accessToken: 'test-token',
        user: mockUser
      };

      service.login(mockEmail, mockPassword).subscribe();

      const req = httpMock.expectOne(`${apiUrl}/login`);
      req.flush(mockResponse);

      expect(localStorage.getItem('current_user')).toBe(JSON.stringify(mockUser));
    });

    it('should update isAuthenticated$ observable to true after successful login', () => new Promise<void>(done => {
      const mockResponse: LoginResponse = {
        accessToken: 'test-token',
        user: { id: '1', email: 'test@example.com', name: 'Test User' }
      };

      service.isAuthenticated$.subscribe(isAuthenticated => {
        if (isAuthenticated) {
          expect(isAuthenticated).toBe(true);
          done();
        }
      });

      service.login('test@example.com', 'ValidPassword123').subscribe();

      const req = httpMock.expectOne(`${apiUrl}/login`);
      req.flush(mockResponse);
    }));

    it('should update currentUser$ observable after successful login', () => new Promise<void>(done => {
      const mockUser = { id: '1', email: 'test@example.com', name: 'Test User' };
      const mockResponse: LoginResponse = {
        accessToken: 'test-token',
        user: mockUser
      };

      service.currentUser$.subscribe(user => {
        if (user && user.id === '1') {
          expect(user).toEqual(mockUser);
          done();
        }
      });

      service.login('test@example.com', 'ValidPassword123').subscribe();

      const req = httpMock.expectOne(`${apiUrl}/login`);
      req.flush(mockResponse);
    }));
  });

  // ==================== Logout ====================
  describe('Logout', () => {
    it('should remove token from localStorage', () => {
      localStorage.setItem('auth_token', 'test-token');

      service.logout();

      expect(localStorage.getItem('auth_token')).toBeNull();
    });

    it('should remove current user from localStorage', () => {
      localStorage.setItem('current_user', JSON.stringify({ id: '1', email: 'test@example.com' }));

      service.logout();

      expect(localStorage.getItem('current_user')).toBeNull();
    });

    it('should remove rememberMe flag from localStorage', () => {
      localStorage.setItem('rememberMe', 'true');

      service.logout();

      expect(localStorage.getItem('rememberMe')).toBeNull();
    });

    it('should update isAuthenticated$ observable to false', () => new Promise<void>(done => {
      localStorage.setItem('auth_token', 'test-token');
      
      service.logout();

      service.isAuthenticated$.subscribe(isAuthenticated => {
        expect(isAuthenticated).toBe(false);
        done();
      });
    }));

    it('should update currentUser$ observable to null', () => new Promise<void>(done => {
      localStorage.setItem('current_user', JSON.stringify({ id: '1', email: 'test@example.com' }));
      
      service.logout();

      service.currentUser$.subscribe(user => {
        expect(user).toBeNull();
        done();
      });
    }));
  });

  // ==================== Get Token ====================
  describe('Get Token', () => {
    it('should return token from localStorage', () => {
      const mockToken = 'test-token-12345';
      localStorage.setItem('auth_token', mockToken);

      const token = service.getToken();

      expect(token).toBe(mockToken);
    });

    it('should return null when token does not exist', () => {
      const token = service.getToken();

      expect(token).toBeNull();
    });
  });

  // ==================== Is Authenticated ====================
  describe('Is Authenticated', () => {
    it('should return true when token exists in localStorage', () => {
      localStorage.setItem('auth_token', 'test-token');

      const isAuthenticated = service.isAuthenticated();

      expect(isAuthenticated).toBe(true);
    });

    it('should return false when token does not exist in localStorage', () => {
      const isAuthenticated = service.isAuthenticated();

      expect(isAuthenticated).toBe(false);
    });
  });

  // ==================== Get Current User ====================
  describe('Get Current User', () => {
    it('should return current user from localStorage', () => {
      const mockUser = { id: '1', email: 'test@example.com', name: 'Test User' };
      localStorage.setItem('current_user', JSON.stringify(mockUser));

      const currentUser = service.getCurrentUser();

      expect(currentUser).toEqual(mockUser);
    });

    it('should return null when current user does not exist', () => {
      const currentUser = service.getCurrentUser();

      expect(currentUser).toBeNull();
    });
  });

  // ==================== Verify Token ====================
  describe('Verify Token', () => {
    it('should send verify request to backend', () => {
      service.verifyToken().subscribe();

      const req = httpMock.expectOne(`${apiUrl}/verify`);
      expect(req.request.method).toBe('POST');
      req.flush({});
    });

    it('should handle verify token success', () => {
      const mockResponse = { valid: true };

      service.verifyToken().subscribe(response => {
        expect(response.valid).toBe(true);
      });

      const req = httpMock.expectOne(`${apiUrl}/verify`);
      req.flush(mockResponse);
    });

    it('should handle verify token failure', () => {
      service.verifyToken().subscribe(
        () => expect.fail('should have failed with 401 error'),
        (error) => {
          expect(error.status).toBe(401);
        }
      );

      const req = httpMock.expectOne(`${apiUrl}/verify`);
      req.flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });
    });
  });

  // ==================== Refresh Token ====================
  describe('Refresh Token', () => {
    it('should send refresh request to backend', () => {
      service.refreshToken().subscribe();

      const req = httpMock.expectOne(`${apiUrl}/refresh`);
      expect(req.request.method).toBe('POST');
      req.flush({ accessToken: 'new-token', user: { id: '1', email: 'test@example.com', name: 'Test User' } });
    });

    it('should update token in localStorage after refresh', () => {
      const mockResponse: LoginResponse = {
        accessToken: 'new-token-456',
        user: { id: '1', email: 'test@example.com', name: 'Test User' }
      };

      service.refreshToken().subscribe();

      const req = httpMock.expectOne(`${apiUrl}/refresh`);
      req.flush(mockResponse);

      expect(localStorage.getItem('auth_token')).toBe('new-token-456');
    });

    it('should handle refresh token failure', () => {
      service.refreshToken().subscribe(
        () => expect.fail('should have failed with 401 error'),
        (error) => {
          expect(error.status).toBe(401);
        }
      );

      const req = httpMock.expectOne(`${apiUrl}/refresh`);
      req.flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });
    });
  });

  // ==================== Password Reset ====================
  describe('Password Reset', () => {
    it('should send the email to the forgot-password endpoint', () => {
      service.requestPasswordReset('test@example.com').subscribe();

      const req = httpMock.expectOne(`${apiUrl}/forgot-password`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ email: 'test@example.com' });
      req.flush(null);
    });

    it('should send the token to the reset-password validate endpoint', () => {
      service.checkResetToken('reset-token').subscribe();

      const req = httpMock.expectOne(`${apiUrl}/reset-password/validate`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ token: 'reset-token' });
      req.flush(null);
    });

    it('should send the token and new password to the reset-password endpoint', () => {
      service.resetPassword('reset-token', 'NewPassword123').subscribe();

      const req = httpMock.expectOne(`${apiUrl}/reset-password`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ token: 'reset-token', newPassword: 'NewPassword123' });
      req.flush(null);
    });

    it('should not sign the user in after a reset', () => {
      service.resetPassword('reset-token', 'NewPassword123').subscribe();
      httpMock.expectOne(`${apiUrl}/reset-password`).flush(null);

      expect(service.isAuthenticated()).toBe(false);
    });

    it('should handle an expired reset token', () => {
      service.resetPassword('expired-token', 'NewPassword123').subscribe(
        () => expect.fail('should have failed with 400 error'),
        (error) => {
          expect(error.status).toBe(400);
        }
      );

      const req = httpMock.expectOne(`${apiUrl}/reset-password`);
      req.flush('Token expired', { status: 400, statusText: 'Bad Request' });
    });
  });

  // ==================== Roles ====================
  // Activity Reporting: the role comes from the token, so routing follows whoever is signed in.
  describe('Roles', () => {
    const tokenWith = (claims: object) =>
      'header.' + btoa(JSON.stringify(claims)).replace(/=+$/, '') + '.signature';

    it('has no role when signed out', () => {
      expect(service.getRole()).toBeNull();
      expect(service.isStaff()).toBe(false);
    });

    it('reads a commercial analyst from the token and sends them to reporting', () => {
      localStorage.setItem('auth_token', tokenWith({ role: 'COMMERCIAL_ANALYST', email: 'analyst@leap.com' }));
      expect(service.getRole()).toBe('COMMERCIAL_ANALYST');
      expect(service.isStaff()).toBe(true);
      expect(service.homeUrl()).toBe('/reporting');
      expect(service.getEmail()).toBe('analyst@leap.com');
    });

    it('treats trading operations as staff', () => {
      localStorage.setItem('auth_token', tokenWith({ role: 'TRADING_OPERATIONS' }));
      expect(service.isStaff()).toBe(true);
      expect(service.homeUrl()).toBe('/reporting');
    });

    it('sends clients, and tokens issued before roles existed, to the dashboard', () => {
      localStorage.setItem('auth_token', tokenWith({ role: 'CLIENT' }));
      expect(service.getRole()).toBe('CLIENT');
      expect(service.homeUrl()).toBe('/dashboard');
      localStorage.setItem('auth_token', tokenWith({ sid: 's' }));
      expect(service.getRole()).toBe('CLIENT');
      expect(service.isStaff()).toBe(false);
    });

    it('never treats an unknown role claim as staff', () => {
      localStorage.setItem('auth_token', tokenWith({ role: 'ADMIN' }));
      expect(service.isStaff()).toBe(false);
    });

    it('has no role for an unreadable token', () => {
      localStorage.setItem('auth_token', 'not-a-jwt');
      expect(service.getRole()).toBeNull();
      expect(service.homeUrl()).toBe('/dashboard');
    });
  });

  // ==================== Helper Methods ====================
  describe('Helper Methods', () => {
    it('should correctly identify when token exists (hasToken)', () => {
      localStorage.setItem('auth_token', 'test-token');
      expect(service['hasToken']()).toBe(true);

      localStorage.removeItem('auth_token');
      expect(service['hasToken']()).toBe(false);
    });

    it('should correctly retrieve user from storage (getUserFromStorage)', () => {
      const mockUser = { id: '1', email: 'test@example.com', name: 'Test User' };
      localStorage.setItem('current_user', JSON.stringify(mockUser));

      const user = service['getUserFromStorage']();
      expect(user).toEqual(mockUser);
    });
  });
});
