import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { PageResult, SelectOption, TreeNode, WorkSpace, WorkSpaceRequest } from './workspace.model';

@Injectable({ providedIn: 'root' })
export class WorkspaceService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/workspaces`;

  list(page = 0, size = 10, search = '', sortBy = '', sortDir = 'desc'): Observable<PageResult<WorkSpace>> {
    return this.http.get<PageResult<WorkSpace>>(this.url, {
      params: { page, size, search, sortBy, sortDir },
    });
  }

  trashed(page = 0, size = 10, search = '', sortBy = '', sortDir = 'desc'): Observable<PageResult<WorkSpace>> {
    return this.http.get<PageResult<WorkSpace>>(`${this.url}/trashed`, {
      params: { page, size, search, sortBy, sortDir },
    });
  }

  tree(): Observable<TreeNode[]> {
    return this.http.get<TreeNode[]>(`${this.url}/tree`);
  }

  forSelect(): Observable<SelectOption[]> {
    return this.http.get<SelectOption[]>(`${this.url}/for-select`);
  }

  get(id: string): Observable<WorkSpace> {
    return this.http.get<WorkSpace>(`${this.url}/${id}`);
  }

  create(body: WorkSpaceRequest): Observable<WorkSpace> {
    return this.http.post<WorkSpace>(this.url, body);
  }

  update(id: string, body: WorkSpaceRequest): Observable<WorkSpace> {
    return this.http.put<WorkSpace>(`${this.url}/${id}`, body);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.url}/${id}`);
  }

  restore(id: string): Observable<void> {
    return this.http.patch<void>(`${this.url}/${id}/restore`, {});
  }

  move(id: string, parentId: string | null): Observable<WorkSpace> {
    return this.http.patch<WorkSpace>(`${this.url}/${id}/parent`, { parentId });
  }

  archive(id: string): Observable<WorkSpace> {
    return this.http.patch<WorkSpace>(`${this.url}/${id}/archive`, {});
  }

  multipleDelete(ids: string[]): Observable<void> {
    return this.http.delete<void>(`${this.url}/multiple-delete`, { body: { ids } });
  }

  multipleRestore(ids: string[]): Observable<void> {
    return this.http.patch<void>(`${this.url}/multiple-restore`, { ids });
  }
}
