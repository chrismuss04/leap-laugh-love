import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SettingsComponent } from './settings';
import { ShellComponent } from '../shell/shell';
import { ClientProfile } from '../services/profile';
import { AccountSummary } from '../services/holdings';

const PROFILE: ClientProfile = {
  clientId: 'c1', email: 'alice@example.com', fullName: 'Alice Example', experienceLevel: 'NOVICE',
  status: 'ACTIVE', createdAt: '2024-01-15T12:00:00Z', phone: '+1-212-555-0101', addressLine1: '1 Main St',
  addressLine2: null, city: 'Springfield', stateRegion: 'IL', postalCode: '62704', countryCode: 'US',
  notifyOrderFills: true, notifyPriceAlerts: false
};

const ACCOUNTS: AccountSummary[] = [
  { accountId: 'a1', accountNumber: 'ACC-0001', status: 'ACTIVE', baseCurrency: 'USD', tradingEnabled: true,
    maxSlippagePercent: null, createdAt: '2024-01-15T12:00:00Z', inactiveSince: null },
  { accountId: 'a2', accountNumber: 'ACC-0002', status: 'ACTIVE', baseCurrency: 'USD', tradingEnabled: true,
    maxSlippagePercent: 1, createdAt: '2024-02-15T12:00:00Z', inactiveSince: null }
];
const ACCOUNTS_URL = '/api/account/accounts';

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
    http.expectOne(ACCOUNTS_URL).flush(ACCOUNTS);
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
    const toggle = element.querySelector<HTMLButtonElement>('[aria-controls="account-settings"]')!;
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

  describe('trading', () => {
    const element = () => fixture.nativeElement as HTMLElement;
    const rows = () => Array.from(element().querySelectorAll<HTMLElement>('[data-testid="protection-row"]'));
    const saveButton = () => Array.from(element().querySelectorAll<HTMLButtonElement>('#trading-settings button.btn-primary'))[0];

    function chip(row: HTMLElement, label: string): HTMLButtonElement {
      const found = Array.from(row.querySelectorAll<HTMLButtonElement>('.chip')).find(c => c.textContent?.trim() === label);
      if (!found) throw new Error(`Missing chip: ${label}`);
      return found;
    }

    function click(row: HTMLElement, label: string): void {
      chip(row, label).click();
      fixture.detectChanges();
    }

    function typeCustom(row: HTMLElement, value: string): void {
      const input = row.querySelector<HTMLInputElement>('.custom-input')!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    }

    it('keeps the section collapsed until opened', () => {
      const toggle = element().querySelector<HTMLButtonElement>('[aria-controls="trading-settings"]')!;
      const body = element().querySelector<HTMLElement>('#trading-settings')!;
      expect(getComputedStyle(body).display).toBe('none');
      toggle.click();
      fixture.detectChanges();
      expect(getComputedStyle(body).display).toBe('flex');
      expect(toggle.getAttribute('aria-expanded')).toBe('true');
    });

    it("shows each account's saved protection and what it means for a $100 order", () => {
      const [first, second] = rows();
      expect(first.textContent).toContain('ACC-0001');
      expect(chip(first, 'Off').getAttribute('aria-checked')).toBe('true');
      expect(first.textContent).toContain('Orders fill at the market price, however far it moves.');
      expect(chip(second, '1%').getAttribute('aria-checked')).toBe('true');
      expect(second.textContent).toContain('At $100.00, your order fills only between $99.00 and $101.00.');
      expect(saveButton().disabled).toBe(true);
    });

    it('saves only the accounts that changed, then confirms', () => {
      const [first] = rows();
      click(first, '0.5%');
      expect(first.textContent).toContain('Unsaved');
      expect(first.textContent).toContain('between $99.50 and $100.50');
      saveButton().click();

      const request = http.expectOne(`${ACCOUNTS_URL}/a1/trade-settings`);
      expect(request.request.method).toBe('PUT');
      expect(request.request.body).toEqual({ maxSlippagePercent: 0.5 });
      request.flush({ ...ACCOUNTS[0], maxSlippagePercent: 0.5 });
      fixture.detectChanges();

      expect(text()).toContain('Your trading settings have been saved.');
      expect(rows()[0].textContent).not.toContain('Unsaved');
      expect(saveButton().disabled).toBe(true);
    });

    it('turns a saved protection off', () => {
      click(rows()[1], 'Off');
      saveButton().click();
      const request = http.expectOne(`${ACCOUNTS_URL}/a2/trade-settings`);
      expect(request.request.body).toEqual({ maxSlippagePercent: null });
      request.flush({ ...ACCOUNTS[1], maxSlippagePercent: null });
    });

    it('takes a custom percent, and blocks saving until it is valid', () => {
      const [first] = rows();
      click(first, 'Custom');
      expect(saveButton().disabled).toBe(true);
      typeCustom(first, '12');
      expect(first.textContent).toContain('Enter a percent from 0 to 10, up to two decimals');
      expect(saveButton().disabled).toBe(true);

      typeCustom(first, '0.75');
      expect(first.textContent).not.toContain('Enter a percent from 0 to 10');
      expect(first.textContent).toContain('between $99.25 and $100.75');
      saveButton().click();
      const request = http.expectOne(`${ACCOUNTS_URL}/a1/trade-settings`);
      expect(request.request.body).toEqual({ maxSlippagePercent: 0.75 });
      request.flush({ ...ACCOUNTS[0], maxSlippagePercent: 0.75 });
    });

    it('says so when saving fails', () => {
      click(rows()[0], '2%');
      saveButton().click();
      http.expectOne(`${ACCOUNTS_URL}/a1/trade-settings`).flush(null, { status: 500, statusText: 'Server Error' });
      fixture.detectChanges();
      expect(text()).toContain("Couldn't save your trading settings. Please try again.");
      expect(rows()[0].textContent).toContain('Unsaved');
    });
  });

  it('says so when the accounts fail to load', () => {
    const failing = TestBed.createComponent(SettingsComponent);
    failing.detectChanges();
    http.expectOne(ACCOUNTS_URL).flush(null, { status: 500, statusText: 'Server Error' });
    failing.detectChanges();
    expect((failing.nativeElement as HTMLElement).textContent).toContain("Couldn't load your accounts.");
  });
});
