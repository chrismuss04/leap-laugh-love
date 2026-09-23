import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ClientProfile {
  clientId: string;
  email: string;
  fullName: string;
  experienceLevel: 'NOVICE' | 'INTERMEDIATE' | 'ADVANCED';
  status: string;
}

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
}
