import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from './api';

export interface Employe {
  id: number;
  firstName: string;
  lastName: string;
  fullName: string;
}

@Injectable({ providedIn: 'root' })
export class EmployeService {
  private http = inject(HttpClient);

  /** Employés ayant un compte utilisateur (candidats approbateurs de workflow). */
  listApprovers(): Observable<Employe[]> {
    return this.http.get<Employe[]>(`${API_BASE}/employes`, { params: { has_user: 1 } });
  }
}
