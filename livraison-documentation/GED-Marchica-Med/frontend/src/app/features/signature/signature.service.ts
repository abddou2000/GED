import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { tap } from 'rxjs/operators';
import { API_BASE } from '../../core/api';
import { Signature } from './signature.model';

@Injectable({ providedIn: 'root' })
export class SignatureService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/signatures`;

  private readonly _revision = signal(0);

  /**
   * Compteur de révision de la file « à traiter ».
   *
   * <p>Le badge du menu affiche un nombre calculé au chargement. Sans ce
   * signal il restait figé : on signait un document, la liste passait à 31 et
   * le badge continuait d'annoncer 32 jusqu'au rechargement de la page. Un
   * compteur faux est pire que pas de compteur — il envoie chercher un travail
   * qui n'existe plus.</p>
   *
   * <p>C'est le service qui prévient, pas l'appelant : un écran qui oublierait
   * de le faire recréerait le défaut, et rien ne le signalerait.</p>
   */
  readonly revision = this._revision.asReadonly();

  /** À appeler après toute action qui ajoute ou retire une étape à traiter. */
  signalerChangement(): void {
    this._revision.update(n => n + 1);
  }

  /*
   * Aucune de ces méthodes ne transmet d'identifiant d'employé : le serveur
   * déduit l'acteur du jeton porté par la requête. Le lui envoyer donnerait
   * l'illusion d'un choix — et c'était précisément la faille : le backend
   * comparait l'assigné de l'étape à cette valeur fournie par le client, donc
   * n'importe qui pouvait signer au nom d'un collègue en changeant un nombre.
   */

  /** Mes signatures à traiter (réellement actionnables). */
  pending(): Observable<Signature[]> {
    return this.http.get<Signature[]>(`${this.url}/pending`);
  }

  /** Mon historique (signé / rejeté). */
  history(): Observable<Signature[]> {
    return this.http.get<Signature[]>(`${this.url}/history`);
  }

  approve(id: number, motif: string | null): Observable<Signature> {
    return this.http.patch<Signature>(`${this.url}/${id}/approve`, { motif })
      .pipe(tap(() => this.signalerChangement()));
  }

  /**
   * Relance un circuit arrêté par un rejet.
   *
   * <p>Sans elle, un refus était définitif : l'étape restait rejetée pour
   * toujours, la suivante bloquée derrière, et le document ne revenait dans
   * aucune file. La pièce était perdue — il fallait la redéposer.</p>
   */
  relancer(documentId: number): Observable<Signature[]> {
    return this.http.patch<Signature[]>(`${this.url}/document/${documentId}/relancer`, {})
      .pipe(tap(() => this.signalerChangement()));
  }

  reject(id: number, motif: string): Observable<Signature> {
    return this.http.patch<Signature>(`${this.url}/${id}/reject`, { motif })
      .pipe(tap(() => this.signalerChangement()));
  }
}
