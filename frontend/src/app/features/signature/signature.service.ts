import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { Signature } from './signature.model';

@Injectable({ providedIn: 'root' })
export class SignatureService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/signatures`;

  /** Mes signatures à traiter (réellement actionnables). */
  pending(employeId: number): Observable<Signature[]> {
    return this.http.get<Signature[]>(`${this.url}/pending`, { params: { employeId } });
  }

  /** Mon historique (signé / rejeté). */
  history(employeId: number): Observable<Signature[]> {
    return this.http.get<Signature[]>(`${this.url}/history`, { params: { employeId } });
  }

  approve(id: number, employeId: number, motif: string | null): Observable<Signature> {
    return this.http.patch<Signature>(`${this.url}/${id}/approve`, { employeId, motif });
  }

  reject(id: number, employeId: number, motif: string): Observable<Signature> {
    return this.http.patch<Signature>(`${this.url}/${id}/reject`, { employeId, motif });
  }
}
