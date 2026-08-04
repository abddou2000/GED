import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { IndexField, IndexRequest, PageResult, SelectOption } from './index.model';

@Injectable({ providedIn: 'root' })
export class IndexService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/indices`;

  list(page = 0, size = 10, search = ''): Observable<PageResult<IndexField>> {
    return this.http.get<PageResult<IndexField>>(this.url, { params: { page, size, search } });
  }

  forSelect(): Observable<SelectOption[]> {
    return this.http.get<SelectOption[]>(`${this.url}/for-select`);
  }

  trashed(page = 0, size = 10, search = ''): Observable<PageResult<IndexField>> {
    return this.http.get<PageResult<IndexField>>(`${this.url}/trashed`, { params: { page, size, search } });
  }

  get(id: number): Observable<IndexField> {
    return this.http.get<IndexField>(`${this.url}/${id}`);
  }

  create(body: IndexRequest): Observable<IndexField> {
    return this.http.post<IndexField>(this.url, body);
  }

  update(id: number, body: IndexRequest): Observable<IndexField> {
    return this.http.put<IndexField>(`${this.url}/${id}`, body);
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
