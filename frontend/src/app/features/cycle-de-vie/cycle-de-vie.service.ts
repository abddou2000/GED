import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, from, map, switchMap, of } from 'rxjs';
import { API_BASE } from '../../core/api';
import {
  Conservation, ElementJob, ExportDossier, IssueExport, JobArchivage, ResultatArchivage,
} from './cycle-de-vie.model';

/**
 * Cycle de vie des documents : purge définitive (§12.5), archivage d'un
 * document ou d'un dossier entier (§12.6, D10), export de dossier (§12.10).
 * Aucune de ces actions n'est automatique.
 */
@Injectable({ providedIn: 'root' })
export class CycleDeVieService {
  private http = inject(HttpClient);

  /* ---------- Purge (corbeille) ---------- */

  purger(id: string): Observable<void> {
    return this.http.post<void>(`${API_BASE}/documents/${id}/purge`, null);
  }

  purgerPlusieurs(ids: string[]): Observable<void> {
    return this.http.post<void>(`${API_BASE}/documents/purge`, { ids });
  }

  /* ---------- Archivage d'un document ---------- */

  archiver(id: string): Observable<ResultatArchivage> {
    return this.http.post<ResultatArchivage>(`${API_BASE}/documents/${id}/archivage`, null);
  }

  desarchiver(id: string): Observable<void> {
    return this.http.delete<void>(`${API_BASE}/documents/${id}/archivage`);
  }

  conservation(id: string): Observable<Conservation> {
    return this.http.get<Conservation>(`${API_BASE}/documents/${id}/conservation`);
  }

  /* ---------- Archivage d'un dossier entier ---------- */

  archiverDossier(dossierId: string): Observable<JobArchivage> {
    return this.http.post<JobArchivage>(`${API_BASE}/archivage/dossiers/${dossierId}`, null);
  }

  statutDossier(dossierId: string): Observable<{ statutConservation: 'ACTIF' | 'ARCHIVE' }> {
    return this.http.get<{ statutConservation: 'ACTIF' | 'ARCHIVE' }>(`${API_BASE}/archivage/dossiers/${dossierId}`);
  }

  retirerDrapeau(dossierId: string): Observable<void> {
    return this.http.delete<void>(`${API_BASE}/archivage/dossiers/${dossierId}`);
  }

  jobs(dossierId?: string): Observable<JobArchivage[]> {
    const params: Record<string, string> = dossierId ? { dossierId } : {};
    return this.http.get<JobArchivage[]>(`${API_BASE}/archivage/jobs`, { params });
  }

  job(id: string): Observable<JobArchivage> {
    return this.http.get<JobArchivage>(`${API_BASE}/archivage/jobs/${id}`);
  }

  annuler(id: string): Observable<JobArchivage> {
    return this.http.post<JobArchivage>(`${API_BASE}/archivage/jobs/${id}/annulation`, null);
  }

  elements(id: string, resultat?: string, page = 0, taille = 50): Observable<ElementJob[]> {
    const params: Record<string, string | number> = { page, taille };
    if (resultat) params['resultat'] = resultat;
    return this.http.get<ElementJob[]>(`${API_BASE}/archivage/jobs/${id}/elements`, { params });
  }

  /* ---------- Export de dossier ---------- */

  /**
   * 200 : l'archive ZIP elle-même ; 202 : un export de fond (au-delà de 500
   * documents ou de 2 Go), à télécharger depuis « Mes exports ».
   */
  exporterDossier(dossierId: string): Observable<IssueExport> {
    return this.http.post(`${API_BASE}/exports/dossiers/${dossierId}`, null,
      { observe: 'response', responseType: 'blob' }).pipe(
      switchMap(r => r.status === 202 && r.body
        ? from(r.body.text()).pipe(map(t => ({ differe: JSON.parse(t) as ExportDossier })))
        : of({ archive: r.body ?? new Blob() })),
    );
  }

  mesExports(): Observable<ExportDossier[]> {
    return this.http.get<ExportDossier[]>(`${API_BASE}/exports`);
  }

  telechargerExport(id: string): Observable<Blob> {
    return this.http.get(`${API_BASE}/exports/${id}/fichier`, { responseType: 'blob' });
  }

  /** Confie un Blob au navigateur (URL d'objet révoquée aussitôt après). */
  enregistrer(blob: Blob, nom: string): void {
    const href = URL.createObjectURL(blob);
    try {
      const a = document.createElement('a');
      a.href = href;
      a.download = nom;
      a.style.display = 'none';
      document.body.appendChild(a);
      a.click();
      a.remove();
    } finally {
      setTimeout(() => URL.revokeObjectURL(href));
    }
  }
}
