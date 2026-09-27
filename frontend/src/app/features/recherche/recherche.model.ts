/** Segment d'extrait : le texte est affiché tel quel, jamais interprété comme du HTML. */
export interface Segment {
  texte: string;
  surligne: boolean;
}

export interface ResultatRecherche {
  documentId: string;
  versionId: string;
  pertinence: number;
  extrait: Segment[];
  nom: string;
  typeDocument: string | null;
  espace: string | null;
  deposeLe: string | null;
  /** ACTIF ou ARCHIVE (badge, §12.6). */
  statutConservation?: 'ACTIF' | 'ARCHIVE' | null;
}

export interface PageResultats {
  resultats: ResultatRecherche[];
  total: number;
  page: number;
  taille: number;
}

export type TriRecherche = 'PERTINENCE' | 'DATE_DEPOT' | 'NOM' | 'TYPE' | 'INDEXATION_RECENTE';

export interface CriteresRecherche {
  q: string;
  tri: TriRecherche;
  typeDocumentId?: string | null;
  workspaceId?: string | null;
  du?: string | null;
  au?: string | null;
  /** Documents archivés : inclus par défaut (§12.6). */
  archives?: 'INCLURE' | 'EXCLURE' | 'SEULEMENT';
}

export type StatutOcr = 'EN_ATTENTE_OCR' | 'EN_COURS_OCR' | 'OCR_TERMINE' | 'OCR_ECHEC';

export interface OcrJob {
  id: string;
  documentId: string;
  versionId: string;
  typeMime: string | null;
  langue: string;
  statut: StatutOcr;
  tentatives: number;
  prochaineTentativeLe: string | null;
  motifEchec: string | null;
  nbPages: number | null;
  deposeLe: string;
  termineLe: string | null;
}

export interface ProgressionReindexation {
  etat: 'INACTIVE' | 'EN_COURS' | 'TERMINEE' | 'ECHOUEE';
  total: number;
  traites: number;
  demarreeLe: string | null;
  termineeLe: string | null;
  erreur: string | null;
}

export interface EtatOcr {
  actif: boolean;
  moteurDisponible: boolean;
  languesInstallees: string[];
  langueDefaut: string;
}
