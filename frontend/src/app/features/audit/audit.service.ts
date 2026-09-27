import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';

/** Enregistrement du journal d'audit (DAT §7.4.1). */
export interface LigneAudit {
  id: number;
  horodatage: string;
  acteurUtilisateurId: string | null;
  acteurApplicationId: string | null;
  acteurNom: string | null;
  adresseIp: string | null;
  action: string;
  objetType: string | null;
  objetId: string | null;
  avant: Record<string, unknown> | null;
  apres: Record<string, unknown> | null;
  resultat: 'SUCCES' | 'REFUS' | 'ECHEC';
  motif: string | null;
  traceId: string | null;
}

export interface PageAudit {
  content: LigneAudit[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

/** Critères de consultation ; les champs vides ne sont pas transmis. */
export interface FiltreAudit {
  du?: string;
  au?: string;
  utilisateur?: string;
  application?: string;
  action?: string;
  objetType?: string;
  objetId?: string;
  resultat?: string;
}

export interface RapportVerification {
  verifieLe: string;
  periodesVerifiees: number;
  enregistrementsVerifies: number;
  scelleJusquA: string | null;
  anomalies: { type: string; periodeDebut: string | null; detail: string }[];
}

/**
 * Journal d'audit : lecture seule (revue technique D11). Aucune méthode de
 * modification ni de suppression n'existe, côté serveur comme ici.
 */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/audit`;

  evenements(filtre: FiltreAudit, page: number, taille: number): Observable<PageAudit> {
    return this.http.get<PageAudit>(`${this.url}/evenements`, { params: { ...params(filtre), page, taille } });
  }

  /** Fichier d'export ; l'en-tête `X-Empreinte-SHA256` porte l'empreinte du contenu. */
  exporter(filtre: FiltreAudit, format: 'csv' | 'json'): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.url}/export`, {
      params: { ...params(filtre), format }, responseType: 'blob', observe: 'response',
    });
  }

  verifier(): Observable<RapportVerification> {
    return this.http.post<RapportVerification>(`${this.url}/verifications`, {});
  }
}

function params(filtre: FiltreAudit): Record<string, string> {
  const p: Record<string, string> = {};
  for (const [cle, valeur] of Object.entries(filtre)) {
    if (typeof valeur === 'string' && valeur.trim()) p[cle] = valeur.trim();
  }
  return p;
}
