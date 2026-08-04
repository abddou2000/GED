/** Type d'un index — détermine le contrôle affiché dans la recherche. */
export type TypeIndex = 'TEXTE' | 'NOMBRE' | 'DATE' | 'LISTE';

/**
 * Critère de recherche, généré par le serveur à partir d'un index.
 * Rien n'est codé en dur côté écran : ajouter un index « indexé pour
 * recherche » fait apparaître un critère supplémentaire.
 */
export interface Critere {
  id: number;
  code: string;
  libelle: string;
  fieldType: TypeIndex;
  options: string[];      // uniquement pour LISTE
  groupage: boolean;
}

/** Filtre envoyé au serveur pour un index donné. */
export interface FiltreIndex {
  indexFieldId: number;
  valeur?: string | null;   // TEXTE (contient) · LISTE (égal)
  de?: string | null;       // DATE / NOMBRE — borne basse
  a?: string | null;        // DATE / NOMBRE — borne haute
}

export interface RechercheRequest {
  workspaceId?: number | null;
  typeDocumentId?: number | null;
  criteres: FiltreIndex[];
  grouperPar?: number | null;
}

/** Une valeur d'index portée par un document. */
export interface ValeurIndex {
  indexFieldId: number;
  code: string;
  libelle: string;
  valeur: string;
}

export interface Resultat {
  id: number;
  name: string;
  extension: string;
  sizeLabel: string;
  workspace: string | null;
  typeDocument: string | null;
  expirationDate: string | null;
  /** Référence composée depuis le plan ; null tant que l'indexation n'a pas été confirmée. */
  reference: string | null;
  valeurs: ValeurIndex[];
}

/** Une valeur proposée par la lecture du document, avec le verdict du contrôle de type. */
export interface Proposition {
  indexFieldId: number;
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
  documentId: number;
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

/** Résultats regroupés (par index de groupage, ou groupe unique « Tous »). */
export interface Groupe {
  libelle: string;
  total: number;
  documents: Resultat[];
}
