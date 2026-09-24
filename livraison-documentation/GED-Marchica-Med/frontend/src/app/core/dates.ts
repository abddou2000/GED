/**
 * Conversions entre la date affichée par le sélecteur Material (un objet
 * {@link Date}) et le format échangé avec l'API (`AAAA-MM-JJ`).
 *
 * <p>Les dates sont construites et relues **composant par composant**, jamais
 * via `new Date('2026-03-12')` : cette forme est interprétée en temps universel,
 * et à l'affichage local elle recule d'un jour dès que le fuseau est négatif.
 * Une date d'expiration n'a pas d'heure — elle ne doit pas dépendre du fuseau.
 */

/** `AAAA-MM-JJ` → date locale, ou `null` si la chaîne n'est pas une date. */
export function versDate(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso.trim());
  if (!m) return null;
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  return Number.isNaN(d.getTime()) ? null : d;
}

/** Date locale → `AAAA-MM-JJ`, ou `null`. */
export function versIso(d: Date | null | undefined): string | null {
  if (!d || Number.isNaN(d.getTime())) return null;
  const deuxChiffres = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${deuxChiffres(d.getMonth() + 1)}-${deuxChiffres(d.getDate())}`;
}

/**
 * Date lisible pour l'affichage : `jj/mm/aaaa`.
 *
 * <p>L'API échange des dates ISO (`2026-05-08`) et des instants ISO
 * (`2026-05-08T10:30:00Z`). Affichées telles quelles, elles se lisent
 * année-mois-jour, ordre que personne n'emploie ici — et « 2026-05-08 » se
 * confond avec le 5 août pour un lecteur pressé.
 *
 * @param vide texte rendu quand il n'y a pas de date
 */
export function formaterDate(iso: string | null | undefined, vide = '—'): string {
  if (!iso) return vide;
  // Une date seule est lue composant par composant : passer par `new Date`
  // l'interpréterait en temps universel et pourrait reculer d'un jour.
  const seule = versDate(iso);
  if (seule && !iso.includes('T')) return format(seule);

  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? vide : format(d);
}

function format(d: Date): string {
  const deuxChiffres = (n: number) => String(n).padStart(2, '0');
  return `${deuxChiffres(d.getDate())}/${deuxChiffres(d.getMonth() + 1)}/${d.getFullYear()}`;
}
