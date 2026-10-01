import { HttpResponse } from '@angular/common/http';

/**
 * Pagination commune des listes et recherches (DAT §5.3.2, T-050) : 50 par
 * défaut, 200 au plus — les bornes appliquées par le serveur (`Tri`). Un écran
 * qui demanderait davantage recevrait silencieusement une page de 200.
 */
export const TAILLE_PAGE_DEFAUT = 50;
export const TAILLE_PAGE_MAX = 200;

/** Choix du sélecteur de taille de page : jamais au-delà du plafond serveur. */
export const TAILLES_PAGE: readonly number[] = [25, 50, 100, TAILLE_PAGE_MAX];

/**
 * En-tête de réponse par lequel le serveur nomme les paramètres ou champs
 * inconnus qu'il a ignorés (DAT §5.3.2, P-08) : noms séparés par des virgules,
 * encodés en pourcentage hors ASCII imprimable.
 */
export const ENTETE_CHAMPS_IGNORES = 'GED-Champs-Ignores';

/** Corps d'une réponse et champs que le serveur a ignorés (en-tête {@link ENTETE_CHAMPS_IGNORES}). */
export interface AvecChampsIgnores<T> {
  corps: T;
  champsIgnores: string[];
}

/** Sépare le corps d'une réponse complète (`observe: 'response'`) et ses champs ignorés. */
export function avecChampsIgnores<T>(r: HttpResponse<T>): AvecChampsIgnores<T> {
  return { corps: r.body as T, champsIgnores: champsIgnores(r.headers.get(ENTETE_CHAMPS_IGNORES)) };
}

/**
 * Noms lisibles des champs ignorés, ou liste vide sans en-tête. Le marqueur
 * de troncature « ... » est conservé tel quel.
 */
export function champsIgnores(valeur: string | null | undefined): string[] {
  if (!valeur) return [];
  return valeur.split(',').map(n => n.trim()).filter(n => n.length > 0).map(n => {
    try {
      return decodeURIComponent(n);
    } catch {
      return n;
    }
  });
}
