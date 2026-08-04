import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { DocumentItem, PageResult } from './document.model';

@Injectable({ providedIn: 'root' })
export class DocumentService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/documents`;

  list(page = 0, size = 10, search = ''): Observable<PageResult<DocumentItem>> {
    return this.http.get<PageResult<DocumentItem>>(this.url, { params: { page, size, search } });
  }

  trashed(page = 0, size = 10, search = ''): Observable<PageResult<DocumentItem>> {
    return this.http.get<PageResult<DocumentItem>>(`${this.url}/trashed`, { params: { page, size, search } });
  }

  upload(file: File, typeDocumentId: number, name: string, expirationDate: string | null): Observable<DocumentItem> {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('typeDocumentId', String(typeDocumentId));
    if (name) fd.append('name', name);
    if (expirationDate) fd.append('expirationDate', expirationDate);
    return this.http.post<DocumentItem>(this.url, fd);
  }

  /** URL de téléchargement direct (attachement). */
  downloadUrl(id: number): string {
    return `${this.url}/${id}/download`;
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
