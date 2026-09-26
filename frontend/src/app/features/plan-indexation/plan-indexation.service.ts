import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { PageResult, PlanIndexation, PlanIndexationRequest, SelectOption } from './plan-indexation.model';

@Injectable({ providedIn: 'root' })
export class PlanIndexationService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/plan-indexations`;

  list(page = 0, size = 10, search = '', sortBy = '', sortDir = 'desc'): Observable<PageResult<PlanIndexation>> {
    return this.http.get<PageResult<PlanIndexation>>(this.url, { params: this.params(page, size, search, sortBy, sortDir) });
  }

  /** Jetons de nommage sans équivalent en base (DATE, YEAR…). */
  jetonsSysteme(): Observable<{ id: string; name: string }[]> {
    return this.http.get<{ id: string; name: string }[]>(`${this.url}/jetons-systeme`);
  }

  forSelect(): Observable<SelectOption[]> {
    return this.http.get<SelectOption[]>(`${this.url}/for-select`);
  }

  trashed(page = 0, size = 10, search = '', sortBy = '', sortDir = 'desc'): Observable<PageResult<PlanIndexation>> {
    return this.http.get<PageResult<PlanIndexation>>(`${this.url}/trashed`, { params: this.params(page, size, search, sortBy, sortDir) });
  }

  /** Le tri n'est transmis que s'il est demandé : sinon le serveur applique son
   *  ordre par défaut (le plus récent d'abord) plutôt qu'un tri vide. */
  private params(page: number, size: number, search: string, sortBy: string, sortDir: string) {
    const p: Record<string, string | number> = { page, size, search };
    if (sortBy) { p['sortBy'] = sortBy; p['sortDir'] = sortDir; }
    return p;
  }

  get(id: string): Observable<PlanIndexation> {
    return this.http.get<PlanIndexation>(`${this.url}/${id}`);
  }

  create(body: PlanIndexationRequest): Observable<PlanIndexation> {
    return this.http.post<PlanIndexation>(this.url, body);
  }

  update(id: string, body: PlanIndexationRequest): Observable<PlanIndexation> {
    return this.http.put<PlanIndexation>(`${this.url}/${id}`, body);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`);
  }

  restore(id: string): Observable<void> {
    return this.http.patch<void>(`${this.url}/${id}/restore`, {});
  }

  multipleDelete(ids: string[]): Observable<void> {
    return this.http.delete<void>(`${this.url}/multiple-delete`, { body: { ids } });
  }

  multipleRestore(ids: string[]): Observable<void> {
    return this.http.patch<void>(`${this.url}/multiple-restore`, { ids });
  }
}
