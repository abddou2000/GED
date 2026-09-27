import { HttpErrorResponse, HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { throwError } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { API_BASE } from './api';
import { AuthService } from './auth.service';

/**
 * Porte le jeton d'accès sur chaque appel à l'API et renouvelle la session en
 * silence (dossier technique §3.4.1).
 *
 * <ul>
 *   <li>L'en-tête `Authorization` n'est ajouté qu'aux appels vers NOTRE API.</li>
 *   <li>Un <b>401</b> sur un appel ordinaire : le jeton d'accès (15 min) a
 *       expiré ou la session a été révoquée. On tente UN renouvellement par le
 *       cookie ; s'il réussit, l'appel est rejoué avec le nouveau jeton ; sinon
 *       la session est fermée et l'utilisateur renvoyé vers la connexion.</li>
 *   <li>Les points d'entrée d'authentification eux-mêmes ne déclenchent jamais
 *       de renouvellement (pas de boucle).</li>
 * </ul>
 * <p>Le <b>403</b> n'est pas traité ici : authentifié mais pas autorisé.
 */
export function authInterceptor(requete: HttpRequest<unknown>, suite: HttpHandlerFn) {
  const auth = inject(AuthService);
  const router = inject(Router);

  const versNotreApi = requete.url.startsWith(API_BASE) || requete.url.includes(`${API_BASE}/`);
  const pointAuth = /\/auth\/(login|refresh|logout)/.test(requete.url);

  const avecJeton = (jeton: string | null) =>
    jeton && versNotreApi && !pointAuth && !requete.headers.has('Authorization')
      ? requete.clone({ setHeaders: { Authorization: `Bearer ${jeton}` } })
      : requete;

  return suite(avecJeton(auth.jeton())).pipe(
    catchError((erreur: HttpErrorResponse) => {
      if (erreur.status !== 401 || !versNotreApi || pointAuth) {
        return throwError(() => erreur);
      }
      return auth.renouveler().pipe(
        catchError(e => {
          auth.oublier();
          router.navigate(['/login'], { queryParams: { expire: 1 } });
          return throwError(() => e);
        }),
        switchMap(r => suite(avecJeton(r.token))),
      );
    }),
  );
}
