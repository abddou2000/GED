import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';

/** Clé d'API telle que l'expose le serveur : jamais le secret ni son empreinte. */
export interface CleApi {
  id: string;
  identifiant: string;
  environnement: string;
  delegation: boolean;
  creeLe: string;
  expireLe: string;
  revoqueeLe: string | null;
  motifRevocation: string | null;
  remplaceeParCleApiId: string | null;
  derniereUtilisation: string | null;
  appelsDuJour: number;
  etat: 'ACTIVE' | 'EN_CHEVAUCHEMENT' | 'EXPIREE' | 'REVOQUEE';
  expireBientot: boolean;
}

export interface ApplicationApi {
  id: string;
  code: string;
  nom: string;
  description: string | null;
  adressesAutorisees: string[];
  quotaMinute: number;
  quotaJour: number;
  active: boolean;
  creeLe: string;
  modifieLe: string | null;
  cles: CleApi[];
  clesExpirantBientot: number;
}

export interface ApplicationRequest {
  code: string;
  nom: string;
  description?: string | null;
  adressesAutorisees: string[];
  quotaMinute: number;
  quotaJour: number;
}

/** Opérations qu'une portée de clé peut permettre (DAT §5.4 ; circuits : contrat E8-API, D8). */
export const OPERATIONS_API = [
  'CONSULTATION', 'RECHERCHE', 'DEPOT', 'CREATION_DOSSIER', 'VERSEMENT', 'RATTACHEMENT',
  'CONSULTATION_DROITS', 'WORKFLOW_PILOTAGE', 'WORKFLOW_DECISION',
] as const;
export type OperationApi = typeof OPERATIONS_API[number];

/** Ligne de portée : un nœud (sous-arborescence comprise) et ses opérations. */
export interface LignePortee {
  noeudId: string;
  noeud?: string | null;
  operations: OperationApi[];
}

/** Réponse d'une génération : `cle` n'est affichée qu'une fois (DAT §5.4). */
export interface CleGeneree {
  cle: string;
  details: CleApi;
}

@Injectable({ providedIn: 'root' })
export class ClesApiService {
  private http = inject(HttpClient);

  lister(): Observable<ApplicationApi[]> {
    return this.http.get<ApplicationApi[]>(`${API_BASE}/applications`);
  }

  creer(req: ApplicationRequest): Observable<ApplicationApi> {
    return this.http.post<ApplicationApi>(`${API_BASE}/applications`, req);
  }

  modifier(id: string, req: ApplicationRequest): Observable<ApplicationApi> {
    return this.http.put<ApplicationApi>(`${API_BASE}/applications/${id}`, req);
  }

  activer(id: string, active: boolean): Observable<ApplicationApi> {
    return this.http.patch<ApplicationApi>(`${API_BASE}/applications/${id}/activation`, { active });
  }

  generer(applicationId: string, delegation: boolean): Observable<CleGeneree> {
    return this.http.post<CleGeneree>(`${API_BASE}/applications/${applicationId}/cles`, { delegation });
  }

  regenerer(cleId: string): Observable<CleGeneree> {
    return this.http.post<CleGeneree>(`${API_BASE}/cles-api/${cleId}/regeneration`, {});
  }

  revoquer(cleId: string, motif: string): Observable<CleApi> {
    return this.http.post<CleApi>(`${API_BASE}/cles-api/${cleId}/revocation`, { motif });
  }

  portee(cleId: string): Observable<LignePortee[]> {
    return this.http.get<LignePortee[]>(`${API_BASE}/cles-api/${cleId}/portee`);
  }

  definirPortee(cleId: string, portee: LignePortee[]): Observable<LignePortee[]> {
    return this.http.put<LignePortee[]>(`${API_BASE}/cles-api/${cleId}/portee`,
      { portee: portee.map(l => ({ noeudId: l.noeudId, operations: l.operations })) });
  }

  /** Espaces et dossiers proposés dans l'éditeur de portée. */
  noeuds(): Observable<{ id: string; name: string }[]> {
    return this.http.get<{ id: string; name: string }[]>(`${API_BASE}/workspaces/for-select`);
  }
}
