/**
 * Pastilles d'identité — source unique.
 *
 * <p>La palette vivait recopiée dans chaque écran qui affiche des personnes.
 * Corriger l'un laissait les autres derrière : les avatars d'un groupe d'accès
 * restaient en aplats saturés quand ceux d'un espace de travail étaient déjà
 * passés en teintes claires.
 *
 * <p>Les aplats pleins alignés verticalement se lisent comme une guirlande :
 * ils attirent l'œil avant le nom qu'ils accompagnent, et leurs verts et rouges
 * entrent en concurrence avec ceux des statuts, à quelques colonnes de là. Le
 * fond clair distingue toujours les personnes sans disputer la hiérarchie au
 * contenu.
 */
const TEINTES: ReadonlyArray<readonly [string, string]> = [
  ['#e3ecf9', '#1d4d80'],
  ['#e2f0e7', '#1e6b41'],
  ['#f7e4e7', '#8e2637'],
  ['#f6ecd9', '#8a6416'],
  ['#ebe6f7', '#523a8f'],
  ['#dff0f2', '#0f6875'],
];

/**
 * Rang dans la palette. Les identifiants sont des UUID opaques : on en tire un
 * entier stable par un hachage simple (la même personne garde sa teinte), sans
 * rien supposer de leur forme.
 */
const rang = (graine: string | number | null | undefined): number => {
  if (typeof graine === 'number') return Math.abs(graine);
  let h = 0;
  for (const c of graine ?? '') h = (h * 31 + c.charCodeAt(0)) | 0;
  return Math.abs(h);
};

const paire = (graine: string | number | null | undefined) => TEINTES[rang(graine) % TEINTES.length];

/** Fond de la pastille, stable pour un même identifiant. */
export const teinteAvatar = (graine: string | number | null | undefined): string => paire(graine)[0];

/** Encre des initiales, assortie au fond. */
export const encreAvatar = (graine: string | number | null | undefined): string => paire(graine)[1];

/** Initiales d'un nom complet — « Sara Bennani » donne « SB ». */
export function initialesDe(nomComplet: string): string {
  const mots = (nomComplet || '').trim().split(/\s+/);
  const a = mots[0]?.[0] ?? '';
  const b = mots.length > 1 ? mots[mots.length - 1][0] : '';
  return (a + b).toUpperCase() || '?';
}

/**
 * Graine stable a partir d'un texte, quand on n'a qu'un nom et pas
 * d'identifiant — la pastille d'une meme personne garde alors sa teinte d'un
 * affichage a l'autre.
 */
export function graineDepuisTexte(texte: string): number {
  let n = 0;
  for (const c of texte || '') n = (n * 31 + c.charCodeAt(0)) % 1000003;
  return n;
}
