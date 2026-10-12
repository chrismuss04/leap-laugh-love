import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { VerifyEmailComponent } from './verify-email.component';

const URL = '/api/iam/v1/clients/register/verify';

describe('VerifyEmailComponent', () => {
  let fixture: ComponentFixture<VerifyEmailComponent>;
  let http: HttpTestingController;

  /** Opens the page the way the emailed link does, leaving the confirmation unanswered. */
  async function arrive(url: string): Promise<void> {
    TestBed.configureTestingModule({
      imports: [VerifyEmailComponent],
      providers: [
        provideRouter([{ path: 'verify-email', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    http = TestBed.inject(HttpTestingController);
    await TestBed.inject(Router).navigateByUrl(url);
    fixture = TestBed.createComponent(VerifyEmailComponent);
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('confirms nothing when the link has no token', async () => {
    await arrive('/verify-email');

    http.expectNone(URL);
    expect(text()).toContain('This confirmation link is invalid or incomplete.');
  });

  it('sends the link\'s token in the body on arrival, keeping it out of request logs', async () => {
    await arrive('/verify-email?token=abc123');

    const request = http.expectOne(URL);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ token: 'abc123' });
    expect(text()).toContain('Confirming your email address...');
    request.flush(null);
  });

  it('tells the visitor the account is open once the link is accepted', async () => {
    await arrive('/verify-email?token=abc123');
    http.expectOne(URL).flush(null);
    fixture.detectChanges();

    expect(text()).toContain('Your email is confirmed and your account is open.');
  });

  it('turns away a dead link without saying why it is dead', async () => {
    await arrive('/verify-email?token=abc123');
    http.expectOne(URL).flush(
      { error: 'INVALID_VERIFICATION_LINK', message: 'server wording' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(text()).toContain('This confirmation link has expired or has already been used.');
    expect(text()).not.toContain('server wording');
  });

  it('asks the visitor to try the same link again when the check could not be made', async () => {
    await arrive('/verify-email?token=abc123');
    http.expectOne(URL).flush(null, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();

    expect(text()).toContain('We couldn\'t confirm your email right now.');
  });
});
