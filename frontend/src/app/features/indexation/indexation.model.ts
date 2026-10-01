import { formaterDate, versDate } from '../../core/dates';

/** Type d'un index — détermine le contrôle affiché dans la recherche. */
export type TypeIndex = 'TEXTE' | 'NOMBRE' | 'DATE' | 'LISTE' | 'BOOLEEN';

/**
 * Critère de recherche, généré par le serveur à partir d'un index.
 * Rien n'est codé en dur côté écran : ajouter un index « indexé pour
 * recherche » fait apparaître un critère supplémentaire.
 */
export interface Critere {
  id: string;
  code: string;
  libelle: string;
  fieldType: TypeIndex;
  options: string[];      // uniquement pour LISTE
  groupage: boolean;
  /** Valeur exigée à l'indexation (le serveur le revérifie). */
  obligatoire?: boolean;
}

/** Une valeur d'index portée par un document. */
export interface ValeurIndex {
  indexFieldId: string;
  code: string;
  libelle: string;
  valeur: string;
}

/** Une valeur proposée par la lecture du document, avec le verdict du contrôle de type. */
export interface Proposition {
  indexFieldId: string;
  code: string;
  libelle: string;
  fieldType: TypeIndex;
  options: string[];
  valeurProposee: string | null;
  /** D'où vient la proposition — le contenu lu dans le document, ou le nom du fichier. */
  source: 'NOM_FICHIER' | 'CONTENU' | null;
  valeurActuelle: string | null;
  reconnue: boolean;
  motif: string | null;
}

/**
 * Résultat d'une analyse — une **proposition**, rien de plus.
 * Aucune valeur n'est enregistrée tant que l'opérateur n'a pas confirmé.
 */
export interface Analyse {
  documentId: string;
  fichier: string;
  planNom: string | null;
  separateur: string;
  majuscule: boolean;
  modeAutomatique: boolean;
  segments: string[];
  propositions: Proposition[];
  referenceProposee: string | null;
  nbReconnus: number;
  nbAttendus: number;
  avertissement: string | null;
  /** Comment le contenu a été lu : couche texte native, OCR, ou pas lu du tout. */
  provenanceTexte: 'COUCHE_TEXTE' | 'OCR' | 'AUCUNE';
  detailTexte: string | null;
}

/** Index déduits d'un nom de fichier, avant dépôt (voir `IndexationService.apercu`). */
export interface Apercu {
  planNom: string | null;
  separateur: string;
  champs: Proposition[];
  nbReconnus: number;
  nbAttendus: number;
  /** Nom que porterait le document s'il était déposé maintenant ; `null` si la
      charte est manuelle ou si rien n'est déductible. */
  nomPropose: string | null;
  /** Ce que la lecture n'a pas su conclure ; `null` si tout a été déduit. */
  avertissement: string | null;
  /** Comment le contenu a été lu — `AUCUNE` si le nom du fichier a suffi.
      L'OCR se trompe : le taire faisait passer sa proposition pour une
      certitude, sans que personne sache qu'il fallait relire. */
  provenanceTexte: 'COUCHE_TEXTE' | 'OCR' | 'AUCUNE';
}

/* ---------- Index booléen (ANO-F-020) ---------- */

/** Écritures d'un booléen admises par le serveur (ValeursMetadonnees.booleen). */
const VRAI = new Set(['true', 'vrai', 'oui', '1', 'o', 'yes']);
const FAUX = new Set(['false', 'faux', 'non', '0', 'n', 'no']);

/** Booléen lu d'une valeur d'index ; `null` si elle est vide ou illisible. */
export function lireBooleen(valeur: unknown): boolean | null {
  if (typeof valeur === 'boolean') return valeur;
  if (valeur == null) return null;
  const s = String(valeur).trim().toLowerCase();
  if (VRAI.has(s)) return true;
  if (FAUX.has(s)) return false;
  return null;
}

/**
 * Valeur d'index telle qu'on l'affiche : « Oui » / « Non » pour un booléen
 * (le serveur renvoie `true` / `false`, ANO-F-020), jj/mm/aaaa pour une date
 * (le serveur renvoie aaaa-mm-jj, ANO-F-025), la valeur telle quelle sinon —
 * y compris une date illisible, plutôt qu'un tiret qui la ferait disparaître.
 */
export function afficherValeurIndex(fieldType: TypeIndex | null | undefined, valeur: unknown): string {
  if (valeur == null) return '';
  if (fieldType === 'BOOLEEN') {
    const b = lireBooleen(valeur);
    if (b !== null) return b ? 'Oui' : 'Non';
  }
  if (fieldType === 'DATE' && typeof valeur === 'string' && versDate(valeur)) {
    return formaterDate(valeur);
  }
  return String(valeur);
}
