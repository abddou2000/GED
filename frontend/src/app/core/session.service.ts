import { Injectable, computed, inject, signal } from '@angular/core';
import { EmployeService, Employe } from './employe.service';

export interface SessionUser {
  id: number | null;
  fullName: string;
  email: string;
}

/**
 * Session « front » — coque d'authentification.
 *
 * L'authentification réelle (Spring Security) est hors périmètre : ce service ne
 * fait que mémoriser, côté navigateur, une identité de démonstration pour
 * personnaliser l'accueil, la barre supérieure et la carte « À valider ».
 * Aucun secret, aucun jeton, aucun appel d'écriture.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private employeService = inject(EmployeService);

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

  /**
   * « Connecte » l'utilisateur (coque UI) : mémorise l'e-mail saisi puis résout une
   * identité de démonstration (le premier approbateur connu) afin de disposer d'un
   * identifiant réel pour les signatures en attente.
   */
  signIn(email: string): void {
    this.user.set({ id: null, fullName: 'Utilisateur', email });
    this.resolveIdentity(email);
  }

  /** Garantit une identité même sans passer par l'écran de connexion (route `/accueil` directe). */
  ensureUser(): void {
    if (this.user()) return;
    this.resolveIdentity(null);
  }

  signOut(): void {
    this.user.set(null);
  }

  private resolveIdentity(email: string | null): void {
    this.employeService.listApprovers().subscribe({
      next: list => {
        const me = list[0];
        if (me) this.user.set({ id: me.id, fullName: me.fullName, email: email ?? this.demoEmail(me) });
      },
      error: () => { /* identité par défaut conservée */ },
    });
  }

  /** E-mail plausible dérivé du nom, pour l'affichage (aucune donnée réelle).
   *  NFD + filtrage [^a-z] retire aussi les accents (marques combinantes). */
  private demoEmail(e: Employe): string {
    const norm = (s: string) => s.toLowerCase().normalize('NFD').replace(/[^a-z]/g, '');
    return `${norm(e.firstName)}.${norm(e.lastName)}@ccistta.ma`;
  }
}
