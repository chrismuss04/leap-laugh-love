import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ShellComponent } from '../shell/shell';
import { ProfileComponent } from './profile';

describe('ProfileComponent', () => {
  const profile = signal<unknown>(null);
  const profileError = signal(false);

  function render(): HTMLElement {
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  beforeEach(() => {
    profile.set(null);
    profileError.set(false);
    TestBed.configureTestingModule({
      imports: [ProfileComponent],
      providers: [{ provide: ShellComponent, useValue: { profile, profileError, experienceLabel: () => 'Intermediate' } }]
    });
  });

  it('shows a loading message until the profile arrives', () => {
    expect(render().textContent).toContain('Loading profile');
  });

  it('shows an error when the profile could not load', () => {
    profileError.set(true);
    expect(render().textContent).toContain('Couldn\'t load your profile');
  });

  it('shows the profile details with a readable country', () => {
    profile.set({
      fullName: 'Ada Lovelace', email: 'ada@example.com', phone: '', addressLine1: '1 Main St', addressLine2: 'Apt 2',
      city: 'London', stateRegion: 'LDN', postalCode: 'N1', countryCode: 'GB', createdAt: '2024-03-01T00:00:00Z'
    });
    const text = render().textContent!;
    expect(text).toContain('Ada Lovelace');
    expect(text).toContain('Apt 2');
    expect(text).toContain('United Kingdom');
    expect(text).toContain('Intermediate');
  });
});
