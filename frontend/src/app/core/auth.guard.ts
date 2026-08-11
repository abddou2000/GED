import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { AuthService } from './auth.service';

/**
 * Barrière d'entrée de la coque applicative.
 *
 * <p>Elle ne remplace pas la sécurité : le serveur refuse de toute façon tout
 * appel sans jeton valide. Son rôle est d'éviter d'ouvrir une interface qui se
 * remplirait d'erreurs, et de renvoyer proprement vers la connexion.
 *
 * <p>Au premier passage, l'identité n'est pas encore connue : on la demande au
 * serveur ({@code /auth/me}) plutôt que de se fier à la présence d'un jeton
 * dans l'onglet — un jeton expiré est présent, mais ne vaut rien.
 *
 * <p>L'adresse demandée est conservée en paramètre : après connexion,
 * l'utilisateur revient là où il allait, et non sur l'accueil.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const versConnexion = () =>
    router.createUrlTree(['/login'], { queryParams: { suite: state.url } });

  if (!auth.jeton()) return versConnexion();

  // Identité déjà résolue pendant cette navigation : rien à revalider.
  if (auth.utilisateur()) return true;

  return auth.reprendreSession().pipe(
    map(() => true),
    catchError(() => {
      auth.deconnexion();
      return of(versConnexion());
    }),
  );
};
