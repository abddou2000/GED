import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { Workflow, WorkflowRequest, PageResult } from './workflow.model';

@Injectable({ providedIn: 'root' })
export class WorkflowService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/workflowgeds`;

  /** Liste active (hors corbeille), paginée + recherche + tri. */
  list(page = 0, size = 10, search = '', sortBy = '', sortDir = 'desc'): Observable<PageResult<Workflow>> {
    return this.http.get<PageResult<Workflow>>(this.url, {
      params: { page, size, search, sortBy, sortDir },
    });
  }

  /** Corbeille (éléments archivés). */
  trashed(page = 0, size = 10, search = '', sortBy = '', sortDir = 'desc'): Observable<PageResult<Workflow>> {
    return this.http.get<PageResult<Workflow>>(`${this.url}/trashed`, {
      params: { page, size, search, sortBy, sortDir },
    });
  }

  get(id: string): Observable<Workflow> {
    return this.http.get<Workflow>(`${this.url}/${id}`);
  }

  create(body: WorkflowRequest): Observable<Workflow> {
    return this.http.post<Workflow>(this.url, body);
  }

  update(id: string, body: WorkflowRequest): Observable<Workflow> {
    return this.http.put<Workflow>(`${this.url}/${id}`, body);
  }

  /** Suppression réversible (corbeille). */
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
