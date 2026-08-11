/**
 * Dates relatives courtes pour les encarts du tableau de bord.
 *
 * <p>Un encart n'a pas la place d'une date complète, et « 06/08/2026 09:15 »
 * n'apprend rien à quelqu'un qui veut savoir si c'est récent. Au-delà d'une
 * semaine en revanche, le relatif devient flou (« il y a 23 jours ») : on
 * repasse alors à la date.</p>
 */
const MINUTE = 60_000;
const HEURE = 60 * MINUTE;
const JOUR = 24 * HEURE;

const JJMM = new Intl.DateTimeFormat('fr-FR', { day: '2-digit', month: '2-digit' });
const JJMMAAAA = new Intl.DateTimeFormat('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });

export function dateCourte(valeur: string | null | undefined): string {
  if (!valeur) return '—';
  const date = new Date(valeur);
  const t = date.getTime();
  if (Number.isNaN(t)) return '—';

  const ecart = Date.now() - t;
  // Une date à venir (horloge du poste en avance, échéance) ne doit pas
  // s'afficher « il y a -3 min ».
  if (ecart < 0) return JJMM.format(date);
  if (ecart < MINUTE) return "à l'instant";
  if (ecart < HEURE) return `il y a ${Math.floor(ecart / MINUTE)} min`;
  if (ecart < JOUR) return `il y a ${Math.floor(ecart / HEURE)} h`;
  if (ecart < 7 * JOUR) return `il y a ${Math.floor(ecart / JOUR)} j`;

  const memeAnnee = date.getFullYear() === new Date().getFullYear();
  return memeAnnee ? JJMM.format(date) : JJMMAAAA.format(date);
}
