import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

/**
 * Mirrors ClientRegistrationController.RegistrationRequest in iam-app.
 * dateOfBirth must be an ISO date string (yyyy-MM-dd), ssn must match XXX-XX-XXXX,
 * countryCode must be a 2-letter ISO code, experienceLevel one of NOVICE|INTERMEDIATE|ADVANCED.
 */
export interface RegistrationRequest {
  email: string;
  phone: string;
  fullName: string;
  dateOfBirth: string;
  ssn: string;
  addressLine1: string;
  addressLine2?: string;
  city: string;
  stateRegion?: string;
  postalCode: string;
  countryCode: string;
  experienceLevel: 'NOVICE' | 'INTERMEDIATE' | 'ADVANCED';
  initialDepositAmount: number;
  password: string;
}

/**
 * The same for every application, whether or not it was stored: the applicant finds out how it
 * went from the email they are sent, never from this.
 */
export interface RegistrationResponse {
  message: string;
}

@Injectable({
  providedIn: 'root'
})
export class ClientRegistrationService {
  private apiUrl = `${environment.apiUrl}${environment.apiEndpoints.clients}`;

  constructor(private httpClient: HttpClient) {}

  register(request: RegistrationRequest): Observable<RegistrationResponse> {
    return this.httpClient.post<RegistrationResponse>(`${this.apiUrl}/register`, request);
  }

  /** Confirm the applicant's email and open their account, using the token from the emailed link */
  verify(token: string): Observable<void> {
    return this.httpClient.post<void>(`${this.apiUrl}/register/verify`, { token });
  }
}
