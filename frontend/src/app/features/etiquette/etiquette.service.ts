import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { Etiquette, EtiquetteRequest, PageResult } from './etiquette.model';

@Injectable({ providedIn: 'root' })
export class EtiquetteService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/etiquettes`;

  list(page = 0, size = 10, search = '', sortBy = '', sortDir = 'desc'): Observable<PageResult<Etiquette>> {
    return this.http.get<PageResult<Etiquette>>(this.url, { params: this.params(page, size, search, sortBy, sortDir) });
  }

  trashed(page = 0, size = 10, search = '', sortBy = '', sortDir = 'desc'): Observable<PageResult<Etiquette>> {
    return this.http.get<PageResult<Etiquette>>(`${this.url}/trashed`, { params: this.params(page, size, search, sortBy, sortDir) });
  }

  /** Le tri n'est transmis que s'il est demandé : sinon le serveur applique son
   *  ordre par défaut (le plus récent d'abord) plutôt qu'un tri vide. */
  private params(page: number, size: number, search: string, sortBy: string, sortDir: string) {
    const p: Record<string, string | number> = { page, size, search };
    if (sortBy) { p['sortBy'] = sortBy; p['sortDir'] = sortDir; }
    return p;
  }

  get(id: string): Observable<Etiquette> {
    return this.http.get<Etiquette>(`${this.url}/${id}`);
  }

  create(body: EtiquetteRequest): Observable<Etiquette> {
    return this.http.post<Etiquette>(this.url, body);
  }

  update(id: string, body: EtiquetteRequest): Observable<Etiquette> {
    return this.http.put<Etiquette>(`${this.url}/${id}`, body);
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
