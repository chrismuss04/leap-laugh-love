import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SettingsComponent } from './settings';
import { ShellComponent } from '../shell/shell';
import { ClientProfile } from '../services/profile';

const PROFILE: ClientProfile = {
  clientId: 'c1', email: 'alice@example.com', fullName: 'Alice Example', experienceLevel: 'NOVICE',
  status: 'ACTIVE', createdAt: '2024-01-15T12:00:00Z', phone: '+1-212-555-0101', addressLine1: '1 Main St',
  addressLine2: null, city: 'Springfield', stateRegion: 'IL', postalCode: '62704', countryCode: 'US',
  notifyOrderFills: true, notifyPriceAlerts: false
};

describe('SettingsComponent', () => {
  let fixture: ComponentFixture<SettingsComponent>;
  let component: SettingsComponent;
  let http: HttpTestingController;
  let shell: { profile: ReturnType<typeof signal<ClientProfile | null>>; profileError: ReturnType<typeof signal<boolean>> };

  beforeEach(() => {
    shell = { profile: signal<ClientProfile | null>(PROFILE), profileError: signal(false) };
    TestBed.configureTestingModule({
      imports: [SettingsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: ShellComponent, useValue: shell }]
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SettingsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('starts from the loaded profile, with the phone formatted for editing', () => {
    expect(component.form.getRawValue()).toEqual(jasmine.objectContaining({
      fullName: 'Alice Example', email: 'alice@example.com', phone: '(212) 555-0101', addressLine1: '1 Main St',
      addressLine2: '', city: 'Springfield', stateRegion: 'IL', postalCode: '62704', countryCode: 'US',
      notifyOrderFills: true, notifyPriceAlerts: false
    }));
  });

  it('saves contact, address and preferences without a password, then confirms and updates the shell', () => {
    component.form.patchValue({ fullName: 'Alice Updated', addressLine1: '2 Oak Ave', addressLine2: 'Apt 4',
      city: 'Toronto', stateRegion: 'ON', postalCode: 'M5V 2T6', countryCode: 'CA', notifyPriceAlerts: true });
    component.save();

    const request = http.expectOne('/api/iam/v1/clients/me');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      fullName: 'Alice Updated', email: 'alice@example.com', phone: '(212) 555-0101', addressLine1: '2 Oak Ave',
      addressLine2: 'Apt 4', city: 'Toronto', stateRegion: 'ON', postalCode: 'M5V 2T6', countryCode: 'CA',
      notifyOrderFills: true, notifyPriceAlerts: true, currentPassword: null, newPassword: null
    });
    const saved = { ...PROFILE, fullName: 'Alice Updated', notifyPriceAlerts: true };
    request.flush(saved);
    fixture.detectChanges();

    expect(shell.profile()).toEqual(saved);
    expect(text()).toContain('Your changes have been saved.');
  });

  it('does not save invalid details', () => {
    component.form.patchValue({ fullName: '', email: 'not-an-email', phone: '(212) 555', addressLine1: '', city: '',
      password: 'short', confirmPassword: 'other' });
    component.save();
    fixture.detectChanges();

    http.expectNone('/api/iam/v1/clients/me');
    expect(text()).toContain('Please enter your name');
    expect(text()).toContain('Please enter a valid email address');
    expect(text()).toContain('Please enter a valid phone number');
    expect(text()).toContain('Please enter your street address');
    expect(text()).toContain('Please enter your city');
    expect(text()).toContain('Password must be at least 8 characters');
    expect(text()).toContain('Passwords do not match');
  });

  it('requires the current password to change the email', () => {
    component.form.patchValue({ email: 'alice.new@example.com' });
    component.save();
    fixture.detectChanges();

    http.expectNone('/api/iam/v1/clients/me');
    expect(text()).toContain('Enter your current password to change your email or password');
  });

  it('keeps the account settings collapsed until the section is opened', () => {
    const element = fixture.nativeElement as HTMLElement;
    const toggle = element.querySelector<HTMLButtonElement>('.section-toggle')!;
    const form = element.querySelector<HTMLFormElement>('#account-settings')!;
    expect(getComputedStyle(form).display).toBe('none');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');

    toggle.click();
    fixture.detectChanges();
    expect(getComputedStyle(form).display).toBe('flex');
    expect(toggle.getAttribute('aria-expanded')).toBe('true');

    toggle.click();
    fixture.detectChanges();
    expect(getComputedStyle(form).display).toBe('none');
  });

  it('lists the current password first and can reveal the current and new passwords', () => {
    const element = fixture.nativeElement as HTMLElement;
    const ids = Array.from(element.querySelectorAll('fieldset:nth-of-type(3) input')).map(input => input.id);
    expect(ids).toEqual(['currentPassword', 'password', 'confirmPassword']);
    const type = (id: string) => element.querySelector<HTMLInputElement>('#' + id)!.type;
    expect([type('currentPassword'), type('password'), type('confirmPassword')]).toEqual(['password', 'password', 'password']);

    element.querySelector<HTMLButtonElement>('[aria-label="Show current password"]')!.click();
    fixture.detectChanges();
    expect(type('currentPassword')).toBe('text');
    expect(type('password')).toBe('password');

    element.querySelector<HTMLButtonElement>('[aria-label="Show new password"]')!.click();
    fixture.detectChanges();
    expect([type('password'), type('confirmPassword')]).toEqual(['text', 'text']);
  });

  it('shows why the server refused the change', () => {
    component.form.patchValue({ email: 'alice.new@example.com', currentPassword: 'WrongPassword1!' });
    component.save();
    http.expectOne('/api/iam/v1/clients/me').flush(null, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(text()).toContain('Your current password is incorrect.');
    expect(shell.profile()).toEqual(PROFILE);
  });
});
