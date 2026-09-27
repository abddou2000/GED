import { HttpErrorResponse, HttpHandlerFn, HttpRequest } from '@angular/common/http';
import { from, Observable, throwError } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { API_BASE } from './api';

/**
 * Réponse d'erreur de l'API : `application/problem+json` (RFC 7807, DAT 5.3.2).
 *
 * `code` est le contrat : on décide sur lui, jamais sur le libellé. `detail`
 * est le message à montrer à l'utilisateur. `erreurs` n'existe que pour un 400
 * de validation, indexé par nom de champ.
 */
export interface ProblemeApi {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  traceId?: string;
  erreurs?: Record<string, string>;
  [extension: string]: unknown;
}

/** Le corps de l'erreur, s'il est au format problem+json. */
export function lireProbleme(err: unknown): ProblemeApi | null {
  const corps = err instanceof HttpErrorResponse ? err.error : (err as { error?: unknown })?.error;
  if (corps && typeof corps === 'object' && !(corps instanceof Blob)
      && ('code' in corps || 'detail' in corps)) {
    return corps as ProblemeApi;
  }
  return null;
}

/** Code métier stable de l'erreur (`FICHIER_INFECTE`, `RESSOURCE_INTROUVABLE`…), ou `null`. */
export function codeErreur(err: unknown): string | null {
  return lireProbleme(err)?.code ?? null;
}

/** Message à afficher : le `detail` du serveur, sinon le libellé par défaut de l'écran. */
export function messageErreur(err: unknown, parDefaut: string): string {
  const detail = lireProbleme(err)?.detail;
  return typeof detail === 'string' && detail.trim() ? detail : parDefaut;
}

/** Erreurs de validation par champ (400 `VALIDATION_ECHOUEE`), vide sinon. */
export function erreursParChamp(err: unknown): Record<string, string> {
  return lireProbleme(err)?.erreurs ?? {};
}

/**
 * Normalise les erreurs de l'API pour tous les écrans.
 *
 * <p>Deux services rendus, sans que chaque écran ait à s'en soucier :
 * <ul>
 *   <li>un corps d'erreur reçu en `Blob` (téléchargement, aperçu) est relu en
 *       JSON quand il est au format problem+json, pour que `detail` et `code`
 *       soient lisibles comme pour tout autre appel ;</li>
 *   <li>les écrans écrits avant le format problem+json lisent `error.message`
 *       et `error.errors` : ces deux alias sont ajoutés, recopiés de `detail`
 *       et `erreurs`. Le code nouveau lit {@link messageErreur} et
 *       {@link erreursParChamp}.</li>
 * </ul>
 */
export function problemeInterceptor(requete: HttpRequest<unknown>, suite: HttpHandlerFn) {
  const versNotreApi = requete.url.startsWith(API_BASE) || requete.url.includes(`${API_BASE}/`);
  if (!versNotreApi) return suite(requete);
  return suite(requete).pipe(
    catchError((erreur: unknown) => {
      if (!(erreur instanceof HttpErrorResponse)) return throwError(() => erreur);
      return normaliser(erreur).pipe(switchMap(normalisee => throwError(() => normalisee)));
    }),
  );
}

function normaliser(erreur: HttpErrorResponse): Observable<HttpErrorResponse> {
  const typeContenu = erreur.headers?.get('Content-Type') ?? '';
  if (erreur.error instanceof Blob && typeContenu.includes('problem+json')) {
    return from(erreur.error.text().then(texte => {
      try {
        return avecAlias(erreur, JSON.parse(texte));
      } catch {
        return erreur;
      }
    }));
  }
  return from(Promise.resolve(lireProbleme(erreur) ? avecAlias(erreur, erreur.error) : erreur));
}

function avecAlias(erreur: HttpErrorResponse, probleme: ProblemeApi): HttpErrorResponse {
  const corps = { ...probleme, message: probleme.detail, errors: probleme.erreurs };
  return new HttpErrorResponse({
    error: corps,
    headers: erreur.headers,
    status: erreur.status,
    statusText: erreur.statusText,
    url: erreur.url ?? undefined,
  });
}
