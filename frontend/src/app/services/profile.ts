import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ClientProfile {
  clientId: string;
  email: string;
  fullName: string;
  experienceLevel: 'NOVICE' | 'INTERMEDIATE' | 'ADVANCED';
  status: string;
  createdAt: string;
  phone: string | null;
  addressLine1: string;
  addressLine2: string | null;
  city: string;
  stateRegion: string | null;
  postalCode: string;
  countryCode: string;
  notifyOrderFills: boolean;
  notifyPriceAlerts: boolean;
}

export type SettingsUpdate = Omit<ClientProfile, 'clientId' | 'experienceLevel' | 'status' | 'createdAt'> & {
  /** Required by the server to change the email or password. */
  currentPassword: string | null;
  /** Null keeps the current password. */
  newPassword: string | null;
};

@Injectable({
  providedIn: 'root'
})
export class ProfileService {
  // Same-origin path, proxied to iam-app - see proxy.conf.js.
  private readonly API_URL = '/api/iam/v1/clients';

  constructor(private http: HttpClient) {}

  getMe(): Observable<ClientProfile> {
    return this.http.get<ClientProfile>(`${this.API_URL}/me`);
  }

  updateMe(update: SettingsUpdate): Observable<ClientProfile> {
    return this.http.put<ClientProfile>(`${this.API_URL}/me`, update);
  }
}
