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

@Injectable({ providedIn: 'root' })
export class StatsService {
  private http = inject(HttpClient);

  overview(): Observable<StatsOverview> {
    return this.http.get<StatsOverview>(`${API_BASE}/stats/overview`);
  }
}
