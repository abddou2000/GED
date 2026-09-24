import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';

/** La lecture automatique est-elle autorisée sur ce serveur ? */
export interface EtatOcr {
  actif: boolean;
}

/**
 * Lecture automatique du contenu (OCRisation).
 *
 * <p>Il n'y a rien à régler depuis l'application : le paramétrage du moteur
 * (langues, résolution, pages lues) vit dans la configuration du serveur. Seul
 * son **état** est consulté ici, pour que la fenêtre de dépôt sache si elle peut
 * proposer l'indexation automatique.
 */
@Injectable({ providedIn: 'root' })
export class OcrService {
  private http = inject(HttpClient);
  private url = `${API_BASE}/ocr`;

  etat(): Observable<EtatOcr> {
    return this.http.get<EtatOcr>(`${this.url}/etat`);
  }
}
