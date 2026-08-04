import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { PageResult, PlanIndexation, PlanIndexationRequest, SelectOption } from './plan-indexation.model';

@Injectable({ providedIn: 'root' })
export class PlanIndexationService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/plan-indexations`;

  list(page = 0, size = 10, search = ''): Observable<PageResult<PlanIndexation>> {
    return this.http.get<PageResult<PlanIndexation>>(this.url, { params: { page, size, search } });
  }

  forSelect(): Observable<SelectOption[]> {
    return this.http.get<SelectOption[]>(`${this.url}/for-select`);
  }

  trashed(page = 0, size = 10, search = ''): Observable<PageResult<PlanIndexation>> {
    return this.http.get<PageResult<PlanIndexation>>(`${this.url}/trashed`, { params: { page, size, search } });
  }

  get(id: number): Observable<PlanIndexation> {
    return this.http.get<PlanIndexation>(`${this.url}/${id}`);
  }

  create(body: PlanIndexationRequest): Observable<PlanIndexation> {
    return this.http.post<PlanIndexation>(this.url, body);
  }

  update(id: number, body: PlanIndexationRequest): Observable<PlanIndexation> {
    return this.http.put<PlanIndexation>(`${this.url}/${id}`, body);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`);
  }

  restore(id: number): Observable<void> {
    return this.http.patch<void>(`${this.url}/${id}/restore`, {});
  }

  multipleDelete(ids: number[]): Observable<void> {
    return this.http.delete<void>(`${this.url}/multiple-delete`, { body: { ids } });
  }

  multipleRestore(ids: number[]): Observable<void> {
    return this.http.patch<void>(`${this.url}/multiple-restore`, { ids });
  }
}
