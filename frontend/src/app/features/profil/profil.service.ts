import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from '../../core/api';
import { Profil } from './profil.model';

@Injectable({ providedIn: 'root' })
export class ProfilService {
  private http = inject(HttpClient);

  /**
   * Fiche d'un utilisateur donné — réservée à une consultation administrateur.
   *
   * <p>Le serveur refuse désormais cet appel si l'identifiant n'est pas celui de
   * l'appelant, sauf rôle administrateur. Pour afficher SA propre fiche, il faut
   * donc {@link courant}, pas cette méthode.
   */
  get(employeId: string): Observable<Profil> {
    return this.http.get<Profil>(`${API_BASE}/employes/${employeId}/profil`);
  }

  /** Fiche de l'utilisateur authentifié : aucun identifiant n'est transmis,
   *  le serveur le lit dans le jeton. */
  courant(): Observable<Profil> {
    return this.http.get<Profil>(`${API_BASE}/employes/profil`);
  }
}
