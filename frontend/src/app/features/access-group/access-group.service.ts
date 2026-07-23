import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { AccessGroup, AccessGroupRequest, PageResult, SelectOption } from './access-group.model';

@Injectable({ providedIn: 'root' })
export class AccessGroupService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/access-groups`;

  list(page = 0, size = 10, search = ''): Observable<PageResult<AccessGroup>> {
    return this.http.get<PageResult<AccessGroup>>(this.url, { params: { page, size, search } });
  }

  trashed(page = 0, size = 10, search = ''): Observable<PageResult<AccessGroup>> {
    return this.http.get<PageResult<AccessGroup>>(`${this.url}/trashed`, { params: { page, size, search } });
  }

  forSelect(): Observable<SelectOption[]> {
    return this.http.get<SelectOption[]>(`${this.url}/for-select`);
  }

  get(id: number): Observable<AccessGroup> {
    return this.http.get<AccessGroup>(`${this.url}/${id}`);
  }

  create(body: AccessGroupRequest): Observable<AccessGroup> {
    return this.http.post<AccessGroup>(this.url, body);
  }

  update(id: number, body: AccessGroupRequest): Observable<AccessGroup> {
    return this.http.put<AccessGroup>(`${this.url}/${id}`, body);
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
