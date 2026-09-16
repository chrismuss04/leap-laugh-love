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
}

export interface RegistrationResponse {
  clientId: string;
  email: string;
  status: string;
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
}
