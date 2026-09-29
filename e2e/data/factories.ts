import { randomInt, randomUUID } from 'node:crypto';

/** Matches the frontend's RegistrationRequest / iam-app's ClientRegistrationController. */
export interface Registration {
  firstName: string;
  lastName: string;
  /** yyyy-MM-dd */
  dateOfBirth: string;
  email: string;
  /** Ten digits; the form formats them as (555) 123-4567. */
  phoneDigits: string;
  password: string;
  streetAddress: string;
  city: string;
  stateRegion: string;
  postalCode: string;
  countryCode: string;
  /** Nine digits; the form formats them as 123-45-6789. */
  ssnDigits: string;
  experience: 'Beginner' | 'Intermediate' | 'Advanced';
  initialDeposit: number;
}

/**
 * A registration no previous run has used. Email and SSN are both unique in iam, and clients
 * can't be deleted, so each call has to generate fresh ones. SSNs start at 800 to stay clear of
 * the seeded 123-xx and 900-xx ranges.
 */
export function uniqueRegistration(overrides: Partial<Registration> = {}): Registration {
  const id = randomUUID().slice(0, 8);
  return {
    firstName: 'Pat',
    lastName: `Tester ${id}`,
    dateOfBirth: '1990-06-15',
    email: `e2e.reg.${id}.${Date.now()}@leap.test`,
    phoneDigits: `555${randomInt(1_000_000, 9_999_999)}`,
    password: 'Password123!',
    streetAddress: '1 Test Street',
    city: 'New York',
    stateRegion: 'NY',
    postalCode: '10001',
    countryCode: 'US',
    ssnDigits: `${randomInt(800, 900)}${randomInt(10, 100)}${randomInt(1000, 10_000)}`,
    experience: 'Intermediate',
    initialDeposit: 5000,
    ...overrides
  };
}

export function formatSsn(digits: string): string {
  return `${digits.slice(0, 3)}-${digits.slice(3, 5)}-${digits.slice(5)}`;
}

export function formatPhone(digits: string): string {
  return `(${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6)}`;
}
