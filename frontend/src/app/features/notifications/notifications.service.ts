import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { API_BASE } from '../../core/api';

/** Notification telle que l'expose le serveur (DAT §12.9). */
export interface NotificationGed {
  id: string;
  type: 'CIRCUIT_OUVERT' | 'CIRCUIT_DECISION' | 'CIRCUIT_ANNULE' | 'ACCES_ESPACE_ATTRIBUE' | 'ECHEANCE_CONSERVATION';
  famille: 'CIRCUIT_VALIDATION' | 'ACCES_ESPACE' | 'FIN_CONSERVATION';
  titre: string;
  message: string;
  /** Chemin relatif dans l'application (ex. `televerser/<id>`), ou null. */
  lien: string | null;
  objetType: string | null;
  objetId: string | null;
  creeLe: string;
  lueLe: string | null;
  lue: boolean;
  courriel: 'A_ENVOYER' | 'ENVOYE' | 'ECHEC' | 'DESACTIVE' | 'SANS_ADRESSE';
}

export interface PageNotifications {
  content: NotificationGed[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface PreferenceNotification {
  courrielActif: boolean;
  inAppActif: boolean;
}

/**
 * Centre de notifications de l'utilisateur connecté. Le compteur des non lues
 * est partagé (signal) : la pastille de la barre supérieure et l'écran de
 * liste restent d'accord sans se parler.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private http = inject(HttpClient);
  private base = `${API_BASE}/notifications`;

  /** Nombre de notifications non lues (pastille). */
  readonly nonLues = signal(0);

  lister(nonLues: boolean, page = 0, size = 50): Observable<PageNotifications> {
    const params = new HttpParams().set('nonLues', nonLues).set('page', page).set('size', size);
    return this.http.get<PageNotifications>(this.base, { params });
  }

  /** Recompte les non lues ; silencieux en cas d'échec (une pastille périmée ne doit rien casser). */
  rafraichirCompteur(): void {
    this.http.get<{ nonLues: number }>(`${this.base}/compteur`).subscribe({
      next: c => this.nonLues.set(c?.nonLues ?? 0),
      error: () => { /* silencieux */ },
    });
  }

  marquerLue(id: string): Observable<NotificationGed> {
    return this.http.post<NotificationGed>(`${this.base}/${id}/lecture`, {})
      .pipe(tap(() => this.rafraichirCompteur()));
  }

  toutMarquerLu(): Observable<{ marquees: number }> {
    return this.http.post<{ marquees: number }>(`${this.base}/lecture`, {})
      .pipe(tap(() => this.nonLues.set(0)));
  }

  preference(): Observable<PreferenceNotification> {
    return this.http.get<PreferenceNotification>(`${this.base}/preferences`);
  }

  definirPreference(courrielActif: boolean): Observable<PreferenceNotification> {
    return this.http.put<PreferenceNotification>(`${this.base}/preferences`, { courrielActif });
  }
}
