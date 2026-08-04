import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { Etiquette, EtiquetteRequest, PageResult } from './etiquette.model';

@Injectable({ providedIn: 'root' })
export class EtiquetteService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/etiquettes`;

  list(page = 0, size = 10, search = ''): Observable<PageResult<Etiquette>> {
    return this.http.get<PageResult<Etiquette>>(this.url, { params: { page, size, search } });
  }

  trashed(page = 0, size = 10, search = ''): Observable<PageResult<Etiquette>> {
    return this.http.get<PageResult<Etiquette>>(`${this.url}/trashed`, { params: { page, size, search } });
  }

  get(id: number): Observable<Etiquette> {
    return this.http.get<Etiquette>(`${this.url}/${id}`);
  }

  create(body: EtiquetteRequest): Observable<Etiquette> {
    return this.http.post<Etiquette>(this.url, body);
  }

  update(id: number, body: EtiquetteRequest): Observable<Etiquette> {
    return this.http.put<Etiquette>(`${this.url}/${id}`, body);
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
