import { Injectable, computed, inject, signal } from '@angular/core';
import { AuthService } from './auth.service';

export interface SessionUser {
  id: string | null;
  fullName: string;
  email: string;
}

/**
 * Identité de la personne connectée, telle que le serveur l'a désignée.
 *
 * <p>Ce service ne devine plus rien. Auparavant il retenait « le premier
 * approbateur de la liste » comme utilisateur courant : commode tant qu'il n'y
 * avait pas d'authentification, mais faux dès qu'elle existe — l'application
 * aurait signé au nom de quelqu'un d'autre. L'identité vient désormais
 * exclusivement de la réponse d'authentification.
 *
 * <p>Il ne détient ni jeton ni secret : {@link AuthService} en est le seul
 * dépositaire. Ici on ne garde que ce qui s'affiche (nom, initiales, e-mail) et
 * l'identifiant d'employé dont les écrans ont besoin pour interroger l'API.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private auth = inject(AuthService);

  readonly user = signal<SessionUser | null>(null);

  /** Prénom pour l'accueil (« Bonjour, {prénom} »). */
  readonly firstName = computed(() => (this.user()?.fullName ?? '').trim().split(/\s+/)[0] || 'Utilisateur');

  /** Initiales pour l'avatar de la barre supérieure. */
  readonly initials = computed(() => {
    const parts = (this.user()?.fullName ?? '').trim().split(/\s+/).filter(Boolean);
    if (!parts.length) return 'GD';
    const first = parts[0][0] ?? '';
    const last = parts.length > 1 ? parts[parts.length - 1][0] ?? '' : '';
    return (first + last).toUpperCase();
  });

  /** Enregistre l'identité renvoyée par la connexion. */
  adopter(id: string, fullName: string, email: string): void {
    this.user.set({ id, fullName, email });
  }

  /**
   * Recopie l'identité déjà résolue par {@link AuthService}.
   *
   * <p>Utile quand un écran s'ouvre après une reprise de session (rechargement
   * de la page) : la garde de route a revalidé le jeton et connaît l'identité,
   * mais ce service n'a pas été renseigné puisqu'on n'est pas passé par le
   * formulaire de connexion.
   */
  ensureUser(): void {
    if (this.user()) return;
    const u = this.auth.utilisateur();
    if (u) this.adopter(u.employeId, u.fullName, u.email);
  }

  /** Ferme la session : le jeton est révoqué et l'identité oubliée. */
  signOut(): void {
    this.user.set(null);
    // AuthService révoque le jeton et vide les deux stockages : sans cet appel,
    // l'onglet suivant rouvrirait la session de la personne précédente.
    this.auth.deconnexion();
  }
}
