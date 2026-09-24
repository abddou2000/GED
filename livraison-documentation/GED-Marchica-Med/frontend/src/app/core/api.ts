/**
 * Base de l'API backend.
 *
 * <p>Chemin **relatif**, et non une adresse absolue : une URL en dur
 * (`http://localhost:8080`) désigne la machine du visiteur, et un site servi en
 * HTTPS ne peut de toute façon pas appeler une API en HTTP — le navigateur
 * bloque la requête. En relatif, la même compilation fonctionne partout.
 *
 * <p>Qui répond derrière dépend du contexte :
 *  - en développement, le proxy d'`ng serve` renvoie vers le backend local
 *    (voir `proxy.conf.json`) ;
 *  - déployé sans serveur, l'intercepteur de démonstration répond à sa place
 *    (voir `demo.interceptor.ts`) ;
 *  - déployé avec un backend, une réécriture de l'hébergeur mappe `/api` dessus.
 */
export const API_BASE = '/api/v1';

/**
 * L'application tourne-t-elle sans backend ?
 *
 * <p>Renseigné au démarrage depuis `assets/config.json`, jamais figé dans le
 * bundle : basculer une instance déployée ne demande pas de recompilation.
 */
export const MODE_DEMO = { actif: false };
