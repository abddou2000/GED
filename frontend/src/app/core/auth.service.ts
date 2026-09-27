import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, finalize, of, shareReplay, tap } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { API_BASE } from './api';

/** Identité renvoyée par la connexion, le renouvellement et `/auth/me`. */
export interface Identite {
  id: string;
  identifiant: string;
  employeId: string;
  fullName: string;
  email: string | null;
  direction: string | null;
  /** Rôles GED : confort d'affichage seulement, le serveur les revérifie à chaque appel. */
  roles: string[];
}

/** Réponse de `/auth/login` et `/auth/refresh`. */
export interface ReponseConnexion {
  token: string;
  tokenType: string;
  expiresIn: number;
  utilisateur: Identite;
}

/** En-tête exigé par le serveur sur les appels fondés sur le cookie (protection CSRF). */
export const ENTETE_RENOUVELLEMENT = 'X-GED-Renouvellement';

/**
 * Authentification côté navigateur (dossier technique §3.4.1).
 *
 * <p><b>Le jeton d'accès vit en mémoire, et nulle part ailleurs</b> : ni
 * `localStorage`, ni `sessionStorage`. Un script injecté dans la page ne peut
 * pas le lire dans un stockage, et il disparaît avec l'onglet. Il est valable
 * 15 minutes.
 *
 * <p><b>La session survit au rechargement grâce au cookie de renouvellement</b>,
 * `HttpOnly` (illisible par JavaScript) et `SameSite=Strict`, posé par le
 * serveur : au démarrage et à chaque 401, un nouveau jeton d'accès est demandé
 * en silence. Un seul renouvellement à la fois : le serveur fait tourner le jeton
 * de renouvellement à chaque usage et traite une seconde présentation du même
 * jeton comme un vol (toute la session est révoquée).
 *
 * <p>Il n'y a plus de « se souvenir de moi » : il reposait sur le stockage du
 * jeton dans le navigateur.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);

  private readonly jetonInterne = signal<string | null>(null);
  readonly utilisateur = signal<Identite | null>(null);

  /** Le jeton courant, pour l'intercepteur. */
  readonly jeton = this.jetonInterne.asReadonly();
  readonly connecte = computed(() => this.jetonInterne() !== null);

  /** Identité provisionnée sans rôle : page d'accueil vide, état attendu (§3.4.2). */
  readonly sansRole = computed(() => {
    const u = this.utilisateur();
    return u !== null && u.roles.length === 0;
  });

  readonly administrateur = computed(() => this.utilisateur()?.roles.includes('ADMINISTRATEUR') ?? false);

  /** Renouvellement en cours, partagé par tous les appels qui l'attendent. */
  private renouvellementEnCours: Observable<ReponseConnexion> | null = null;

  /** Connexion par l'identifiant Windows (sAMAccountName), jamais par l'adresse e-mail. */
  connexion(identifiant: string, motDePasse: string): Observable<ReponseConnexion> {
    return this.http
      .post<ReponseConnexion>(`${API_BASE}/auth/login`, { identifiant, motDePasse }, { withCredentials: true })
      .pipe(tap(r => this.ouvrir(r)));
  }

  /**
   * Nouveau jeton d'accès à partir du cookie de renouvellement. Les appels
   * simultanés partagent le même aller-retour.
   */
  renouveler(): Observable<ReponseConnexion> {
    if (!this.renouvellementEnCours) {
      this.renouvellementEnCours = this.http
        .post<ReponseConnexion>(`${API_BASE}/auth/refresh`, null, {
          headers: new HttpHeaders({ [ENTETE_RENOUVELLEMENT]: '1' }),
          withCredentials: true,
        })
        .pipe(
          tap(r => this.ouvrir(r)),
          finalize(() => { this.renouvellementEnCours = null; }),
          shareReplay(1),
        );
    }
    return this.renouvellementEnCours;
  }

  /**
   * Reprise de session au démarrage ou après un rechargement : le jeton d'accès
   * n'a pas survécu (mémoire), le cookie oui.
   */
  reprendreSession(): Observable<ReponseConnexion> {
    return this.renouveler();
  }

  /**
   * Déconnexion : la session est révoquée côté serveur (le jeton d'accès cesse
   * aussitôt d'être accepté, le cookie est effacé). La mémoire locale est vidée
   * d'abord : se déconnecter ne doit pas dépendre du réseau.
   */
  deconnexion(): Observable<unknown> {
    const jeton = this.jetonInterne();
    this.oublier();
    let headers = new HttpHeaders({ [ENTETE_RENOUVELLEMENT]: '1' });
    if (jeton) headers = headers.set('Authorization', `Bearer ${jeton}`);
    return this.http.post(`${API_BASE}/auth/logout`, null, { headers, withCredentials: true })
      .pipe(catchError(() => of(null)));
  }

  /** Oubli local seulement (session refusée par le serveur). */
  oublier(): void {
    this.jetonInterne.set(null);
    this.utilisateur.set(null);
  }

  private ouvrir(r: ReponseConnexion): void {
    this.jetonInterne.set(r.token);
    this.utilisateur.set(r.utilisateur);
  }
}
