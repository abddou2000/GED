import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { AuthService } from './auth.service';

/**
 * Barrière d'entrée de la coque applicative.
 *
 * <p>Elle ne remplace pas la sécurité : le serveur refuse tout appel sans jeton
 * valable. Le jeton d'accès vivant en mémoire, il n'existe pas encore après un
 * rechargement : la garde demande alors un renouvellement silencieux (cookie
 * `HttpOnly`). S'il échoue, direction la connexion, en retenant l'adresse visée.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.jeton() && auth.utilisateur()) return true;

  return auth.reprendreSession().pipe(
    map(() => true),
    catchError(() => {
      auth.oublier();
      return of(router.createUrlTree(['/login'], { queryParams: { suite: state.url } }));
    }),
  );
};

/**
 * Écrans réservés aux identités qui ont au moins un rôle GED. Une identité tout
 * juste provisionnée (sans rôle) reste sur la page d'accueil vide : c'est l'état
 * attendu (§3.4.2), pas une anomalie. Le serveur refuse de toute façon (403).
 */
export const roleGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.sansRole() ? inject(Router).createUrlTree(['/accueil']) : true;
};

/** Écrans d'administration : rôle Administrateur. */
export const administrateurGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.administrateur() ? true : inject(Router).createUrlTree(['/accueil']);
};
