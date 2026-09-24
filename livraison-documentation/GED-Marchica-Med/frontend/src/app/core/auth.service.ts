import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { API_BASE } from './api';

/** Ce que l'API renvoie à la connexion et sur `/auth/me`. */
export interface ReponseConnexion {
  token: string | null;
  tokenType: string | null;
  expiresIn: number;
  employeId: number;
  email: string;
  fullName: string;
}

/** Clé de reprise de session dans l'onglet. */
const CLE_JETON = 'ged.jeton';

/**
 * Authentification côté navigateur.
 *
 * <p><b>Où vit le jeton.</b> En mémoire pendant la navigation, et recopié dans
 * {@code sessionStorage} par défaut — ou dans {@code localStorage} si la
 * personne a coché « Se souvenir de moi ». La différence compte :
 * {@code sessionStorage} est cloisonné à l'onglet et disparaît à sa fermeture,
 * là où {@code localStorage} survivrait indéfiniment sur un poste partagé. Le
 * jeton reste néanmoins lisible par du JavaScript exécuté dans la page : c'est
 * la contrepartie assumée du transport par en-tête, et la raison pour laquelle
 * sa durée de vie est courte côté serveur (2 h).
 *
 * <p><b>Ce qui fait autorité.</b> Jamais ce service. Le jeton est signé par le
 * serveur et revalidé à chaque requête : ce qui est stocké ici ne sert qu'à
 * afficher l'identité, pas à décider de quoi que ce soit.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);

  private readonly jetonInterne = signal<string | null>(this.lireJetonMemorise());
  readonly utilisateur = signal<ReponseConnexion | null>(null);

  /** Le jeton courant, pour l'intercepteur. */
  readonly jeton = this.jetonInterne.asReadonly();
  readonly connecte = computed(() => this.jetonInterne() !== null);

  /**
   * @param memoriser « Se souvenir de moi » : le jeton est alors écrit dans
   *   {@code localStorage}, qui survit à la fermeture de l'onglet. Sans la
   *   case, il reste dans {@code sessionStorage} et disparaît avec l'onglet —
   *   c'est le comportement par défaut, et le plus sûr sur un poste partagé.
   */
  connexion(email: string, motDePasse: string, memoriser = false): Observable<ReponseConnexion> {
    return this.http
      .post<ReponseConnexion>(`${API_BASE}/auth/login`, { email, motDePasse })
      .pipe(tap(r => this.ouvrir(r, memoriser)));
  }

  /**
   * Revalide le jeton mémorisé auprès du serveur.
   *
   * <p>Indispensable au démarrage : un jeton présent dans l'onglet peut avoir
   * expiré, ou son compte avoir été désactivé entre-temps. Se fier à sa seule
   * présence afficherait une application dont chaque appel répondrait 401.
   */
  reprendreSession(): Observable<ReponseConnexion> {
    return this.http
      .get<ReponseConnexion>(`${API_BASE}/auth/me`)
      .pipe(tap(r => this.appliquerIdentite(r)));
  }

  /**
   * Ferme la session côté navigateur.
   *
   * <p>L'appel serveur est informatif : sans état, il n'y a rien à invalider.
   * On efface donc localement d'abord — la déconnexion ne doit pas dépendre de
   * la disponibilité du réseau.
   */
  deconnexion(): void {
    this.jetonInterne.set(null);
    this.utilisateur.set(null);
    // Les DEUX stockages sont vidés : se déconnecter doit fermer la session,
    // qu'elle ait été mémorisée ou non.
    this.effacerJeton();
  }

  private ouvrir(r: ReponseConnexion, memoriser: boolean): void {
    if (r.token) {
      this.jetonInterne.set(r.token);
      /* On efface d'abord les deux : sans cela, un jeton laissé par une session
         mémorisée survivrait à une connexion NON mémorisée, et l'onglet suivant
         rouvrirait la session de la personne précédente. */
      this.effacerJeton();
      try {
        (memoriser ? localStorage : sessionStorage).setItem(CLE_JETON, r.token);
      } catch {
        // Stockage refusé : la session vit alors le temps de la page.
      }
    }
    this.appliquerIdentite(r);
  }

  private effacerJeton(): void {
    try { sessionStorage.removeItem(CLE_JETON); } catch { /* stockage indisponible */ }
    try { localStorage.removeItem(CLE_JETON); } catch { /* stockage indisponible */ }
  }

  private appliquerIdentite(r: ReponseConnexion): void {
    this.utilisateur.set(r);
  }

  private lireJetonMemorise(): string | null {
    try {
      // L'onglet d'abord — une session ouverte ici prime sur une session
      // mémorisée plus ancienne.
      return sessionStorage.getItem(CLE_JETON) ?? localStorage.getItem(CLE_JETON);
    } catch {
      // Stockage interdit (navigation privée verrouillée, politique d'entreprise) :
      // la session vivra alors le temps de la page, sans reprise possible.
      return null;
    }
  }
}
