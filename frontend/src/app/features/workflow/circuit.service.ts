import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map, tap } from 'rxjs/operators';
import { API_BASE } from '../../core/api';
import { TAILLE_PAGE_MAX } from '../../core/pagination';
import {
  ATraiter, Anomalie, Circuit, DecisionRendue, PageATraiter, RegleDocument, TypeDecision,
} from './circuit.model';

/**
 * API du workflow de validation (`/api/v1/workflow`, §12.8). Aucune méthode ne
 * transmet l'identité : le serveur la lit dans le jeton (ou, pour l'intranet,
 * dans la délégation de la clé d'API).
 */
@Injectable({ providedIn: 'root' })
export class CircuitService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/workflow`;

  private readonly _revision = signal(0);

  /**
   * Révision de la file « à traiter » : le badge du menu et l'encart du
   * tableau de bord se recalculent quand elle change. C'est le service qui
   * prévient, pour qu'aucun écran ne puisse l'oublier.
   */
  readonly revision = this._revision.asReadonly();

  signalerChangement(): void {
    this._revision.update(n => n + 1);
  }

  /** Ce que la personne connectée a à décider : une page au plafond du serveur (200, DAT §5.3.2). */
  aTraiter(): Observable<ATraiter[]> {
    return this.http.get<PageATraiter>(`${this.url}/a-traiter`, { params: { page: 0, size: TAILLE_PAGE_MAX } })
      .pipe(map(p => p.content));
  }

  /** Nombre d'éléments à traiter (badge). */
  nombreATraiter(): Observable<number> {
    return this.http.get<PageATraiter>(`${this.url}/a-traiter`, { params: { page: 0, size: 1 } })
      .pipe(map(p => p.total));
  }

  historique(): Observable<DecisionRendue[]> {
    return this.http.get<DecisionRendue[]>(`${this.url}/historique`);
  }

  anomalies(): Observable<Anomalie[]> {
    return this.http.get<Anomalie[]>(`${this.url}/anomalies`);
  }

  circuitsDuDocument(documentId: string): Observable<Circuit[]> {
    return this.http.get<Circuit[]>(`${this.url}/documents/${documentId}/circuits`);
  }

  regleDuDocument(documentId: string): Observable<RegleDocument | null> {
    return this.http.get<RegleDocument | null>(`${this.url}/documents/${documentId}/regle`);
  }

  /** Décision sur un circuit ; sans validateur désigné, elle vaut pour ceux que la personne incarne. */
  decider(circuitId: string, decision: TypeDecision, motif: string | null, validateurId?: string): Observable<Circuit> {
    return this.http.post<Circuit>(`${this.url}/circuits/${circuitId}/decisions`,
      { decision, motif, validateurId: validateurId ?? null })
      .pipe(tap(() => this.signalerChangement()));
  }

  annuler(circuitId: string, motif: string): Observable<Circuit> {
    return this.http.post<Circuit>(`${this.url}/circuits/${circuitId}/annulation`, { motif })
      .pipe(tap(() => this.signalerChangement()));
  }

  /** Nouveau circuit, après une annulation : la règle applicable aujourd'hui est figée. */
  ouvrir(documentId: string): Observable<Circuit> {
    return this.http.post<Circuit>(`${this.url}/documents/${documentId}/circuits`, {})
      .pipe(tap(() => this.signalerChangement()));
  }

  /** Réaffectation manuelle d'un validateur en attente (Administrateur, D1). */
  reaffecter(circuitId: string, validateurId: string, employeId: string, motif: string): Observable<Circuit> {
    return this.http.put<Circuit>(`${this.url}/circuits/${circuitId}/validateurs/${validateurId}`,
      { employeId, motif })
      .pipe(tap(() => this.signalerChangement()));
  }

  /** Diffusion d'un document validé : lecture accordée, sans copie. */
  diffuser(documentId: string, utilisateurIds: string[], groupeIds: string[]): Observable<{ habilitationsPosees: number }> {
    return this.http.post<{ habilitationsPosees: number }>(`${this.url}/documents/${documentId}/diffusion`,
      { utilisateurIds, groupeIds });
  }

  rattacherNoeud(noeudId: string, regleId: string | null): Observable<void> {
    return this.http.put<void>(`${this.url}/noeuds/${noeudId}/regle`, { regleId });
  }

  rattacherType(typeId: string, regleId: string | null): Observable<void> {
    return this.http.put<void>(`${this.url}/types/${typeId}/regle`, { regleId });
  }
}
