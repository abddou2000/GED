import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { IndexField, IndexRequest, PageResult, SelectOption } from './index.model';

@Injectable({ providedIn: 'root' })
export class IndexService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/indices`;

  list(page = 0, size = 10, search = '', sortBy = '', sortDir = 'desc'): Observable<PageResult<IndexField>> {
    return this.http.get<PageResult<IndexField>>(this.url, { params: this.params(page, size, search, sortBy, sortDir) });
  }

  forSelect(): Observable<SelectOption[]> {
    return this.http.get<SelectOption[]>(`${this.url}/for-select`);
  }

  trashed(page = 0, size = 10, search = '', sortBy = '', sortDir = 'desc'): Observable<PageResult<IndexField>> {
    return this.http.get<PageResult<IndexField>>(`${this.url}/trashed`, { params: this.params(page, size, search, sortBy, sortDir) });
  }

  /** Le tri n'est transmis que s'il est demandé : sinon le serveur applique son
   *  ordre par défaut (le plus récent d'abord) plutôt qu'un tri vide. */
  private params(page: number, size: number, search: string, sortBy: string, sortDir: string) {
    const p: Record<string, string | number> = { page, size, search };
    if (sortBy) { p['sortBy'] = sortBy; p['sortDir'] = sortDir; }
    return p;
  }

  get(id: string): Observable<IndexField> {
    return this.http.get<IndexField>(`${this.url}/${id}`);
  }

  create(body: IndexRequest): Observable<IndexField> {
    return this.http.post<IndexField>(this.url, body);
  }

  update(id: string, body: IndexRequest): Observable<IndexField> {
    return this.http.put<IndexField>(`${this.url}/${id}`, body);
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
