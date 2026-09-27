import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import {
  CriteresRecherche, EtatOcr, OcrJob, PageResultats, ProgressionReindexation, StatutOcr,
} from './recherche.model';

/**
 * Recherche plein texte (§4.4) et supervision des traitements OCR (§4.3.4).
 */
@Injectable({ providedIn: 'root' })
export class RechercheService {
  private http = inject(HttpClient);

  rechercher(c: CriteresRecherche, page: number, taille: number): Observable<PageResultats> {
    const params: Record<string, string | number> = { q: c.q, tri: c.tri, page, taille };
    if (c.typeDocumentId) params['typeDocumentId'] = c.typeDocumentId;
    if (c.workspaceId) params['workspaceId'] = c.workspaceId;
    if (c.du) params['du'] = c.du;
    if (c.au) params['au'] = c.au;
    if (c.archives && c.archives !== 'INCLURE') params['archives'] = c.archives;
    if (c.canal) params['canal'] = c.canal;
    return this.http.get<PageResultats>(`${API_BASE}/recherche/plein-texte`, { params });
  }

  etat(): Observable<EtatOcr> {
    return this.http.get<EtatOcr>(`${API_BASE}/ocr/etat`);
  }

  compteurs(): Observable<Record<StatutOcr, number>> {
    return this.http.get<Record<StatutOcr, number>>(`${API_BASE}/admin/ocr/compteurs`);
  }

  jobs(statut: StatutOcr, page = 0, taille = 50): Observable<OcrJob[]> {
    return this.http.get<OcrJob[]>(`${API_BASE}/admin/ocr/jobs`, { params: { statut, page, taille } });
  }

  relancer(id: string): Observable<void> {
    return this.http.post<void>(`${API_BASE}/admin/ocr/jobs/${id}/relance`, null);
  }

  lancerReindexation(): Observable<ProgressionReindexation> {
    return this.http.post<ProgressionReindexation>(`${API_BASE}/admin/recherche/reindexation`, null);
  }

  progressionReindexation(): Observable<ProgressionReindexation> {
    return this.http.get<ProgressionReindexation>(`${API_BASE}/admin/recherche/reindexation`);
  }
}
