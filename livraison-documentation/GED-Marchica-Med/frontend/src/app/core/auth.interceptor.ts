import { HttpErrorResponse, HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { API_BASE } from './api';
import { AuthService } from './auth.service';

/**
 * Porte le jeton sur chaque appel à l'API, et traite le refus du serveur.
 *
 * <p>Deux règles, dans cet ordre :
 * <ul>
 *   <li>l'en-tête n'est ajouté qu'aux appels vers NOTRE API. Un jeton envoyé à
 *       une adresse tierce — une police de caractères, une carte — serait remis
 *       à un serveur qui n'a rien à en connaître ;</li>
 *   <li>un <b>401</b> signifie que le serveur ne reconnaît plus la session :
 *       jeton expiré, compte désactivé, clé de signature changée. On ferme la
 *       session localement et on renvoie vers la connexion, plutôt que de
 *       laisser l'utilisateur devant des écrans qui échouent en silence.</li>
 * </ul>
 *
 * <p>Le <b>403</b> n'est pas traité ici : il veut dire « authentifié mais pas
 * autorisé ». Déconnecter dans ce cas serait faux — la session est valide, c'est
 * l'action qui ne l'est pas. L'écran concerné affiche le refus.
 */
export function authInterceptor(requete: HttpRequest<unknown>, suite: HttpHandlerFn) {
  const auth = inject(AuthService);
  const router = inject(Router);

  const versNotreApi = requete.url.startsWith(API_BASE) || requete.url.includes(`${API_BASE}/`);
  const jeton = auth.jeton();

  // La connexion elle-même ne porte pas de jeton : c'est elle qui le délivre.
  const estConnexion = requete.url.includes(`${API_BASE}/auth/login`);

  const requeteFinale = jeton && versNotreApi && !estConnexion
    ? requete.clone({ setHeaders: { Authorization: `Bearer ${jeton}` } })
    : requete;

  return suite(requeteFinale).pipe(
    catchError((erreur: HttpErrorResponse) => {
      if (erreur.status === 401 && !estConnexion) {
        auth.deconnexion();
        router.navigate(['/login'], { queryParams: { expire: 1 } });
      }
      return throwError(() => erreur);
    }),
  );
}
