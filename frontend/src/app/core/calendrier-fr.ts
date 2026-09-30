import { Injectable } from '@angular/core';
import {
  MatDatepicker, MatDatepickerInput, MatDatepickerIntl, MatDatepickerToggle, MatDatepickerToggleIcon,
} from '@angular/material/datepicker';

/**
 * Ce qu'un écran importe pour un champ date, à la place de `MatDatepickerModule`.
 *
 * <p>`MatDatepickerModule` déclare lui-même `MatDatepickerIntl` dans ses
 * fournisseurs : importé par un composant autonome, il lui crée un injecteur
 * qui masque la version française fournie par la configuration de
 * l'application, et les libellés repassent en anglais (ANO-F-024). Les
 * directives autonomes n'apportent aucun fournisseur.
 */
export const CHAMP_DATE = [MatDatepicker, MatDatepickerInput, MatDatepickerToggle, MatDatepickerToggleIcon] as const;

/**
 * Libellés français du sélecteur de date Material (ANO-F-024).
 *
 * <p>Sans ce fournisseur, Material sert ses textes d'origine : « Open
 * calendar », « Next month », « Choose month and year »… lus par les lecteurs
 * d'écran et affichés en info-bulle, en anglais, dans une interface française.
 * Seuls les libellés sont traduits ici ; le format des dates relève de
 * l'adaptateur de dates.
 */
@Injectable()
export class CalendrierFr extends MatDatepickerIntl {
  override calendarLabel = 'Calendrier';
  override openCalendarLabel = 'Ouvrir le calendrier';
  override closeCalendarLabel = 'Fermer le calendrier';
  override prevMonthLabel = 'Mois précédent';
  override nextMonthLabel = 'Mois suivant';
  override prevYearLabel = 'Année précédente';
  override nextYearLabel = 'Année suivante';
  override prevMultiYearLabel = 'Années précédentes';
  override nextMultiYearLabel = 'Années suivantes';
  override switchToMonthViewLabel = 'Choisir une date';
  override switchToMultiYearViewLabel = "Choisir le mois et l'année";
  override startDateLabel = 'Date de début';
  override endDateLabel = 'Date de fin';
  override comparisonDateLabel = 'Période de comparaison';

  /** « 2020 – 2043 ». */
  override formatYearRange(debut: string, fin: string): string {
    return `${debut} – ${fin}`;
  }

  /** Version lue par les lecteurs d'écran : « de 2020 à 2043 ». */
  override formatYearRangeLabel(debut: string, fin: string): string {
    return `de ${debut} à ${fin}`;
  }
}
