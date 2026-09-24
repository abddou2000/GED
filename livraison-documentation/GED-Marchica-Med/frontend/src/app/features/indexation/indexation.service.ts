import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { Analyse, Apercu, Critere, ValeurIndex } from './indexation.model';

@Injectable({ providedIn: 'root' })
export class IndexationService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/indexation`;

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

  /**
   * Index déduits d'un nom de fichier, AVANT tout dépôt — ce que le formulaire
   * de téléversement affiche dès que l'opérateur choisit son fichier.
   *
   * Le découpage est fait par le serveur, pas ici : séparateur du plan, ordre
   * des index, contrôle de type et lecture des dates y vivent déjà. Les
   * réécrire en TypeScript les ferait diverger au premier changement de règle.
   */
  apercu(typeDocumentId: number, fichier: File): Observable<Apercu> {
    /* Le fichier accompagne la demande : le serveur lit son contenu quand le
       nom ne suffit pas. Il n'est PAS conservé — il est recopié dans un
       temporaire, lu, puis supprimé. Le dépôt reste un geste distinct. */
    const fd = new FormData();
    fd.append('file', fichier);
    fd.append('typeDocumentId', String(typeDocumentId));
    fd.append('nomFichier', fichier.name);
    return this.http.post<Apercu>(`${API_BASE}/indexation/apercu`, fd);
  }
}
