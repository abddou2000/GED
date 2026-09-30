import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, finalize, map, shareReplay, tap } from 'rxjs/operators';
import { API_BASE } from './api';

/** Modules métier activables par configuration (T-088, DAT §9.3) ; le socle n'en est pas un. */
export type CodeModule = 'ocr' | 'workflow' | 'cycledevie' | 'export' | 'notifications' | 'integration';

/** Un module et son état sur l'environnement, tel que `GET /modules` le décrit. */
export interface EtatModule {
  code: string;
  libelle: string;
  reference: string;
  actif: boolean;
}

/**
 * État des modules métier de l'environnement (T-088).
 *
 * <p>Le front masque les menus et ferme les routes d'un module désactivé :
 * c'est du confort. La sécurité reste côté serveur, qui répond 404
 * `MODULE_INACTIF` sur toutes les routes du module.
 *
 * <p>Tant que l'état n'est pas connu (pas encore chargé, serveur injoignable,
 * réponse illisible), un module est tenu pour actif : masquer à tort un module
 * déployé serait pire qu'afficher un menu que le serveur refusera.
 */
@Injectable({ providedIn: 'root' })
export class ModulesService {
  private http = inject(HttpClient);

  /** Code → actif ; `null` tant que le serveur n'a pas répondu. */
  private readonly etats = signal<Record<string, boolean> | null>(null);
  private chargementEnCours: Observable<void> | null = null;

  /** Le module est-il actif sur cet environnement ? (inconnu = actif) */
  actif(code: CodeModule): boolean {
    return this.etats()?.[code] !== false;
  }

  /**
   * Charge l'état des modules une fois pour toutes (il ne change qu'au
   * redémarrage du serveur). Ne lève jamais : en cas d'échec, l'état reste
   * inconnu et un appel ultérieur réessaiera.
   */
  charger(): Observable<void> {
    if (this.etats() !== null) return of(undefined);
    if (!this.chargementEnCours) {
      this.chargementEnCours = this.http.get<EtatModule[]>(`${API_BASE}/modules`).pipe(
        tap(liste => {
          // Mode démonstration : pas de liste, tout est actif.
          const etats: Record<string, boolean> = {};
          if (Array.isArray(liste)) for (const m of liste) etats[m.code] = m.actif;
          this.etats.set(etats);
        }),
        map(() => undefined),
        catchError(() => of(undefined)),
        finalize(() => { this.chargementEnCours = null; }),
        shareReplay(1),
      );
    }
    return this.chargementEnCours;
  }
}
