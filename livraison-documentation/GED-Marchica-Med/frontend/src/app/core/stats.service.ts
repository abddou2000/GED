import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from './api';

export interface StatsOverview {
  workspaces: number;
  documents: number;
  pendingSignatures: number;
  accessGroups: number;
}

/** Une part de la répartition des documents par type. */
export interface PartType {
  label: string;
  count: number;
}

/** Un point de la courbe des dépôts : une journée (AAAA-MM-JJ), un total. */
export interface PointDepot {
  date: string;
  count: number;
}

@Injectable({ providedIn: 'root' })
export class StatsService {
  private http = inject(HttpClient);

  overview(): Observable<StatsOverview> {
    return this.http.get<StatsOverview>(`${API_BASE}/stats/overview`);
  }

  /** Répartition des documents par type, la plus fournie d'abord. */
  parType(): Observable<PartType[]> {
    return this.http.get<PartType[]>(`${API_BASE}/stats/par-type`);
  }

  /**
   * Dépôts jour par jour. Le serveur complète les journées sans dépôt : la
   * courbe reçue couvre toujours la fenêtre entière, sans trou à combler ici.
   */
  depots(jours = 30): Observable<PointDepot[]> {
    return this.http.get<PointDepot[]>(`${API_BASE}/stats/depots`, { params: { jours } });
  }
}
