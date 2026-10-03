import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, map, of } from 'rxjs';
import { API_BASE } from './api';

export interface Employe {
  id: string;
  firstName: string;
  lastName: string;
  fullName: string;
  /** Identité GED de la personne ; absente si elle n'a pas de compte. */
  utilisateurId?: string | null;
}

/**
 * Une personne ayant un compte, désignée par son identité GED : c'est
 * l'identifiant que portent la désignation d'un document confidentiel, le
 * critère « déposant » et l'auteur d'une version (ANO-F-012, ANO-F-013).
 */
export interface Personne {
  utilisateurId: string;
  nom: string;
}

@Injectable({ providedIn: 'root' })
export class EmployeService {
  private http = inject(HttpClient);

  /** Employés ayant un compte utilisateur (candidats approbateurs de workflow). */
  listApprovers(): Observable<Employe[]> {
    return this.http.get<Employe[]>(`${API_BASE}/employes`, { params: { has_user: 1 } });
  }

  /**
   * Toutes les fiches employé, avec ou sans identité GED (`utilisateurId`
   * absent pour une personne qui ne s'est encore jamais connectée). Sert aux
   * groupes, qui acceptent une fiche sans identité comme membre en attente de
   * première connexion (T-025, ANO-F-029).
   */
  tous(): Observable<Employe[]> {
    return this.http.get<Employe[]>(`${API_BASE}/employes`);
  }

  /**
   * Personnes ayant une identité GED, triées par nom. Un échec rend une liste
   * vide (les noms retombent alors sur « — »), jamais une erreur.
   */
  personnes(): Observable<Personne[]> {
    return this.listApprovers().pipe(
      map(l => l.filter(e => !!e.utilisateurId)
        .map(e => ({ utilisateurId: e.utilisateurId!, nom: e.fullName }))
        .sort((a, b) => a.nom.localeCompare(b.nom, 'fr'))),
      catchError(() => of([] as Personne[])),
    );
  }
}
