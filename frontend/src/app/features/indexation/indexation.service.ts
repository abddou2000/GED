import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { Analyse, Critere, Groupe, RechercheRequest, ValeurIndex } from './indexation.model';

@Injectable({ providedIn: 'root' })
export class IndexationService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/indexation`;

  /** Critères de recherche dérivés des index « indexé pour recherche ». */
  criteres(): Observable<Critere[]> {
    return this.http.get<Critere[]>(`${this.url}/criteres`);
  }

  /** Index utilisables pour regrouper les résultats. */
  groupages(): Observable<Critere[]> {
    return this.http.get<Critere[]>(`${this.url}/groupages`);
  }

  /** Champs à renseigner pour un document (issus du plan d'indexation de son type). */
  champs(documentId: number): Observable<Critere[]> {
    return this.http.get<Critere[]>(`${this.url}/documents/${documentId}/champs`);
  }

  /**
   * Lit le document (couche texte ou reconnaissance optique) et **propose** ses
   * valeurs d'index ; le nom du fichier sert de raccourci quand il est parlant.
   * Lecture seule : appeler cette méthode n'enregistre rien, c'est
   * `enregistrer()` qui valide, après confirmation humaine.
   */
  analyser(documentId: number): Observable<Analyse> {
    return this.http.get<Analyse>(`${this.url}/documents/${documentId}/analyse`);
  }

  /** Valeurs d'index actuellement portées par un document. */
  valeurs(documentId: number): Observable<ValeurIndex[]> {
    return this.http.get<ValeurIndex[]>(`${this.url}/documents/${documentId}`);
  }

  /** Enregistre les valeurs d'index d'un document. */
  enregistrer(documentId: number, valeurs: { indexFieldId: number; valeur: string | null }[]): Observable<ValeurIndex[]> {
    return this.http.put<ValeurIndex[]>(`${this.url}/documents/${documentId}`, { valeurs });
  }

  /** Recherche multi-critères ; les résultats reviennent regroupés. */
  rechercher(requete: RechercheRequest): Observable<Groupe[]> {
    return this.http.post<Groupe[]>(`${this.url}/recherche`, requete);
  }
}
