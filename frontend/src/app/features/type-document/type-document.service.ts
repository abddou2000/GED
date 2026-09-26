import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { PageResult, SelectOption, TypeDocument, TypeDocumentRequest } from './type-document.model';

@Injectable({ providedIn: 'root' })
export class TypeDocumentService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/type-documents`;

  list(page = 0, size = 10, search = '', sortBy = '', sortDir = 'desc'): Observable<PageResult<TypeDocument>> {
    return this.http.get<PageResult<TypeDocument>>(this.url, { params: this.params(page, size, search, sortBy, sortDir) });
  }

  forSelect(): Observable<SelectOption[]> {
    return this.http.get<SelectOption[]>(`${this.url}/for-select`);
  }

  trashed(page = 0, size = 10, search = '', sortBy = '', sortDir = 'desc'): Observable<PageResult<TypeDocument>> {
    return this.http.get<PageResult<TypeDocument>>(`${this.url}/trashed`, { params: this.params(page, size, search, sortBy, sortDir) });
  }

  /** Le tri n'est transmis que s'il est demandé : sinon le serveur applique son
   *  ordre par défaut (le plus récent d'abord) plutôt qu'un tri vide. */
  private params(page: number, size: number, search: string, sortBy: string, sortDir: string) {
    const p: Record<string, string | number> = { page, size, search };
    if (sortBy) { p['sortBy'] = sortBy; p['sortDir'] = sortDir; }
    return p;
  }

  get(id: string): Observable<TypeDocument> {
    return this.http.get<TypeDocument>(`${this.url}/${id}`);
  }

  create(body: TypeDocumentRequest): Observable<TypeDocument> {
    return this.http.post<TypeDocument>(this.url, body);
  }

  update(id: string, body: TypeDocumentRequest): Observable<TypeDocument> {
    return this.http.put<TypeDocument>(`${this.url}/${id}`, body);
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
