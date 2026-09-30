import { EnvironmentProviders, Injectable, Provider, makeEnvironmentProviders } from '@angular/core';
import { DateAdapter, MAT_DATE_FORMATS, MAT_DATE_LOCALE, MAT_NATIVE_DATE_FORMATS, NativeDateAdapter } from '@angular/material/core';

/**
 * Adaptateur de dates français pour les sélecteurs Material (ANO-F-022).
 *
 * <p>L'adaptateur natif lit une saisie au clavier avec `Date.parse`, donc à
 * l'américaine (mm/jj/aaaa), alors que le champ affiche jj/mm/aaaa : « 15/03/2026 »
 * était refusé et « 03/04/2026 » (3 avril) enregistré au 4 mars, sans
 * avertissement. Ici, une saisie se lit **jour / mois / année** (séparateurs
 * « / », « . » ou « - », année sur quatre chiffres), ou au format ISO
 * AAAA-MM-JJ ; une date impossible (31/02) est invalide, jamais reportée au
 * mois suivant. L'affichage dans le champ est jj/mm/aaaa.</p>
 */
@Injectable()
export class DateAdapterFr extends NativeDateAdapter {
  override parse(value: unknown, parseFormat?: unknown): Date | null {
    if (value == null || value === '') return null;
    if (typeof value === 'number') return new Date(value);
    if (value instanceof Date) return this.clone(value);
    if (typeof value !== 'string') return this.invalid();
    const texte = value.trim();
    if (!texte) return null;

    const fr = /^(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})$/.exec(texte);
    if (fr) return this.composer(Number(fr[3]), Number(fr[2]), Number(fr[1]));
    const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(texte);
    if (iso) return this.composer(Number(iso[1]), Number(iso[2]), Number(iso[3]));
    return this.invalid();
  }

  override format(date: Date, displayFormat: object): string {
    if (!this.isValid(date)) return super.format(date, displayFormat);
    // Le champ de saisie (format `dateInput`) : jj/mm/aaaa, relu tel quel par `parse`.
    if (displayFormat === MAT_NATIVE_DATE_FORMATS.display.dateInput) {
      const deux = (n: number) => String(n).padStart(2, '0');
      return `${deux(date.getDate())}/${deux(date.getMonth() + 1)}/${date.getFullYear()}`;
    }
    return super.format(date, displayFormat);
  }

  /** Semaine du lundi au dimanche, comme le calendrier français. */
  override getFirstDayOfWeek(): number {
    return 1;
  }

  /** Date locale à minuit, ou invalide si le jour n'existe pas dans ce mois. */
  private composer(annee: number, mois: number, jour: number): Date {
    if (mois < 1 || mois > 12 || jour < 1 || jour > 31) return this.invalid();
    const d = new Date(annee, mois - 1, jour);
    return d.getFullYear() === annee && d.getMonth() === mois - 1 && d.getDate() === jour ? d : this.invalid();
  }
}

/** Fournisseurs des dates en français : adaptateur, locale et formats Material. */
export function provideDateAdapterFr(): EnvironmentProviders {
  const fournisseurs: Provider[] = [
    { provide: DateAdapter, useClass: DateAdapterFr },
    { provide: MAT_DATE_LOCALE, useValue: 'fr-FR' },
    { provide: MAT_DATE_FORMATS, useValue: MAT_NATIVE_DATE_FORMATS },
  ];
  return makeEnvironmentProviders(fournisseurs);
}
