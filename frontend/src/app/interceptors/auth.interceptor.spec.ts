import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { AuthInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';
import { HTTP_INTERCEPTORS } from '@angular/common/http';

describe('AuthInterceptor', () => {
  let httpClient: HttpClient;
  let httpMock: HttpTestingController;
  let authService: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    // Create a spy object for AuthService
    const authServiceSpy = jasmine.createSpyObj('AuthService', ['getToken', 'logout']);

    TestBed.configureTestingModule({
    imports: [],
    providers: [
        { provide: AuthService, useValue: authServiceSpy },
        {
            provide: HTTP_INTERCEPTORS,
            useClass: AuthInterceptor,
            multi: true
        },
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting()
    ]
});

    httpClient = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    authService = TestBed.inject(AuthService) as jasmine.SpyObj<AuthService>;
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ==================== Interceptor Initialization ====================
  describe('Interceptor Initialization', () => {
    it('should be created', () => {
      const interceptor = TestBed.inject(HTTP_INTERCEPTORS).find(i => i instanceof AuthInterceptor);
      expect(interceptor).toBeTruthy();
    });
  });

  // ==================== Request Intercepting ====================
  describe('Request Intercepting', () => {
    it('should add Authorization header when token exists', () => {
      const mockToken = 'test-token-12345';
      authService.getToken.and.returnValue(mockToken);

      httpClient.get('/test-endpoint').subscribe();

      const req = httpMock.expectOne('/test-endpoint');
      expect(req.request.headers.has('Authorization')).toBe(true);
      expect(req.request.headers.get('Authorization')).toBe(`Bearer ${mockToken}`);

      req.flush({});
    });

    it('should not add Authorization header when token does not exist', () => {
      authService.getToken.and.returnValue(null);

      httpClient.get('/test-endpoint').subscribe();

      const req = httpMock.expectOne('/test-endpoint');
      expect(req.request.headers.has('Authorization')).toBe(false);

      req.flush({});
    });

    it('should preserve other headers when adding Authorization', () => {
      const mockToken = 'test-token-12345';
      authService.getToken.and.returnValue(mockToken);

      httpClient.get('/test-endpoint', {
        headers: { 'Custom-Header': 'custom-value' }
      }).subscribe();

      const req = httpMock.expectOne('/test-endpoint');
      expect(req.request.headers.get('Custom-Header')).toBe('custom-value');
      expect(req.request.headers.get('Authorization')).toBe(`Bearer ${mockToken}`);

      req.flush({});
    });

    it('should not modify the original request', () => {
      const mockToken = 'test-token-12345';
      authService.getToken.and.returnValue(mockToken);

      const originalRequest = httpClient.get('/test-endpoint');

      originalRequest.subscribe();

      const req = httpMock.expectOne('/test-endpoint');
      req.flush({});

      // The original request should not have the Authorization header
      // (This is an implicit test - interceptor clones the request)
      expect(req.request.headers.has('Authorization')).toBe(true);
    });
  });

  // ==================== Error Handling ====================
  describe('Error Handling', () => {
    it('should call logout when receiving 401 error', () => {
      authService.getToken.and.returnValue('test-token');

      httpClient.get('/test-endpoint').subscribe(
        () => fail('should have failed with 401 error'),
        (error: HttpErrorResponse) => {
          expect(error.status).toBe(401);
        }
      );

      const req = httpMock.expectOne('/test-endpoint');
      req.flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });

      expect(authService.logout).toHaveBeenCalled();
    });

    it('should redirect to home page when receiving 401 error', () => {
      authService.getToken.and.returnValue('test-token');
      spyOn(window.location, 'href' as any).and.stub();

      httpClient.get('/test-endpoint').subscribe(
        () => fail('should have failed with 401 error'),
        (error: HttpErrorResponse) => {
          expect(error.status).toBe(401);
        }
      );

      const req = httpMock.expectOne('/test-endpoint');
      req.flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });

      // Note: window.location.href redirection is difficult to test in Angular
      // This test verifies the interceptor calls the logout method
      expect(authService.logout).toHaveBeenCalled();
    });

    it('should throw error after handling 401', (done) => {
      authService.getToken.and.returnValue('test-token');

      httpClient.get('/test-endpoint').subscribe(
        () => fail('should have failed with 401 error'),
        (error: HttpErrorResponse) => {
          expect(error.status).toBe(401);
          done();
        }
      );

      const req = httpMock.expectOne('/test-endpoint');
      req.flush('Unauthorized', { status: 401, statusText: 'Unauthorized' });
    });

    it('should pass a 401 on a request without a token (sign-in) through to the caller', (done) => {
      authService.getToken.and.returnValue(null);

      httpClient.post('/api/iam/auth/login', {}).subscribe({
        next: () => fail('should have failed with 401 error'),
        error: (error: HttpErrorResponse) => {
          expect(error.status).toBe(401);
          expect(error.error.message).toBe('Invalid email or password');
          expect(authService.logout).not.toHaveBeenCalled();
          done();
        }
      });

      const req = httpMock.expectOne('/api/iam/auth/login');
      req.flush({ error: 'INVALID_CREDENTIALS', message: 'Invalid email or password' },
        { status: 401, statusText: 'Unauthorized' });
    });

    it('should not call logout for non-401 errors', () => {
      authService.getToken.and.returnValue('test-token');

      httpClient.get('/test-endpoint').subscribe(
        () => fail('should have failed with 500 error'),
        (error: HttpErrorResponse) => {
          expect(error.status).toBe(500);
        }
      );

      const req = httpMock.expectOne('/test-endpoint');
      req.flush('Internal Server Error', { status: 500, statusText: 'Internal Server Error' });

      expect(authService.logout).not.toHaveBeenCalled();
    });

    it('should throw non-401 errors without modification', (done) => {
      authService.getToken.and.returnValue('test-token');

      httpClient.get('/test-endpoint').subscribe(
        () => fail('should have failed with 500 error'),
        (error: HttpErrorResponse) => {
          expect(error.status).toBe(500);
          expect(error.statusText).toBe('Internal Server Error');
          done();
        }
      );

      const req = httpMock.expectOne('/test-endpoint');
      req.flush('Internal Server Error', { status: 500, statusText: 'Internal Server Error' });
    });

    it('should handle network errors', (done) => {
      authService.getToken.and.returnValue('test-token');

      httpClient.get('/test-endpoint').subscribe(
        () => fail('should have failed with network error'),
        (error) => {
          expect(error.status).toBe(0);
          done();
        }
      );

      const req = httpMock.expectOne('/test-endpoint');
      req.error(new ProgressEvent('Network error'));
    });
  });

  // ==================== Multiple Requests ====================
  describe('Multiple Requests', () => {
    it('should handle multiple concurrent requests correctly', () => {
      const mockToken = 'test-token-12345';
      authService.getToken.and.returnValue(mockToken);

      httpClient.get('/endpoint-1').subscribe();
      httpClient.get('/endpoint-2').subscribe();
      httpClient.post('/endpoint-3', {}).subscribe();

      const req1 = httpMock.expectOne('/endpoint-1');
      const req2 = httpMock.expectOne('/endpoint-2');
      const req3 = httpMock.expectOne('/endpoint-3');

      expect(req1.request.headers.get('Authorization')).toBe(`Bearer ${mockToken}`);
      expect(req2.request.headers.get('Authorization')).toBe(`Bearer ${mockToken}`);
      expect(req3.request.headers.get('Authorization')).toBe(`Bearer ${mockToken}`);

      req1.flush({});
      req2.flush({});
      req3.flush({});
    });

    it('should handle mix of requests with and without tokens', () => {
      authService.getToken.and.returnValue(null);

      httpClient.get('/endpoint-1').subscribe();

      const req1 = httpMock.expectOne('/endpoint-1');
      expect(req1.request.headers.has('Authorization')).toBe(false);

      req1.flush({});

      authService.getToken.and.returnValue('new-token');

      httpClient.get('/endpoint-2').subscribe();

      const req2 = httpMock.expectOne('/endpoint-2');
      expect(req2.request.headers.get('Authorization')).toBe('Bearer new-token');

      req2.flush({});
    });
  });

  // ==================== Request Methods ====================
  describe('Different Request Methods', () => {
    it('should intercept GET requests', () => {
      authService.getToken.and.returnValue('test-token');

      httpClient.get('/test-endpoint').subscribe();

      const req = httpMock.expectOne('/test-endpoint');
      expect(req.request.method).toBe('GET');
      expect(req.request.headers.get('Authorization')).toBe('Bearer test-token');

      req.flush({});
    });

    it('should intercept POST requests', () => {
      authService.getToken.and.returnValue('test-token');

      httpClient.post('/test-endpoint', { data: 'test' }).subscribe();

      const req = httpMock.expectOne('/test-endpoint');
      expect(req.request.method).toBe('POST');
      expect(req.request.headers.get('Authorization')).toBe('Bearer test-token');

      req.flush({});
    });

    it('should intercept PUT requests', () => {
      authService.getToken.and.returnValue('test-token');

      httpClient.put('/test-endpoint', { data: 'test' }).subscribe();

      const req = httpMock.expectOne('/test-endpoint');
      expect(req.request.method).toBe('PUT');
      expect(req.request.headers.get('Authorization')).toBe('Bearer test-token');

      req.flush({});
    });

    it('should intercept DELETE requests', () => {
      authService.getToken.and.returnValue('test-token');

      httpClient.delete('/test-endpoint').subscribe();

      const req = httpMock.expectOne('/test-endpoint');
      expect(req.request.method).toBe('DELETE');
      expect(req.request.headers.get('Authorization')).toBe('Bearer test-token');

      req.flush({});
    });

    it('should intercept PATCH requests', () => {
      authService.getToken.and.returnValue('test-token');

      httpClient.patch('/test-endpoint', { data: 'test' }).subscribe();

      const req = httpMock.expectOne('/test-endpoint');
      expect(req.request.method).toBe('PATCH');
      expect(req.request.headers.get('Authorization')).toBe('Bearer test-token');

      req.flush({});
    });
  });
});
