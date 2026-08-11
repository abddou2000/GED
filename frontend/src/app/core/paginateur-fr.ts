import { Injectable } from '@angular/core';
import { MatPaginatorIntl } from '@angular/material/paginator';

/**
 * Libellés français du paginateur Material.
 *
 * <p>Sans ce fournisseur, Material sert ses textes d'origine : « Items per
 * page », « 1 – 9 of 9 », « Next page ». Ils apparaissaient sous chaque
 * tableau de l'application, en anglais, au milieu d'une interface française.
 */
@Injectable()
export class PaginateurFr extends MatPaginatorIntl {
  override itemsPerPageLabel = 'Lignes par page :';
  override nextPageLabel = 'Page suivante';
  override previousPageLabel = 'Page précédente';
  override firstPageLabel = 'Première page';
  override lastPageLabel = 'Dernière page';

  /** « 1 – 10 sur 42 ». L'espace insécable évite une coupure en fin de ligne. */
  override getRangeLabel = (page: number, taille: number, total: number): string => {
    if (total === 0 || taille === 0) return `0 sur ${total}`;
    const debut = page * taille;
    const fin = Math.min(debut + taille, total);
    return `${debut + 1} – ${fin} sur ${total}`;
  };
}
