export interface Ref {
  id: string;
  label: string;
}

/** Un document déposé tel que renvoyé par l'API. */
export interface DocumentItem {
  id: string;
  name: string;
  workspace: Ref | null;
  typeDocument: Ref | null;
  fileName: string;
  extension: string;
  sizeKo: number;
  sizeLabel: string;
  expirationDate: string | null;
  active: boolean;
  verrouille: boolean;
  /** Rangement lisible : dossier / type. */
  chemin: string;
  createdBy: string | null;
  etiquettes: Tag[];
  versions: Version[];
  createdAt: string;
  /** Niveau de confidentialité (§12.3). */
  confidentialite?: Confidentialite;
  /**
   * Permissions de l'appelant sur ce document (fiche seulement) : l'interface
   * masque les actions qu'il ne peut pas exercer. Confort : le serveur décide.
   */
  permissions?: string[] | null;
  /** Emplacements complémentaires visibles (fiche seulement). */
  rattachements?: Ref[] | null;
  /** Socle commun (§12.7). */
  objet?: string | null;
  dateDocument?: string | null;
  metadonnees?: Record<string, unknown>;
  /** Conservation (§12.6, §12.9). */
  statutConservation?: 'ACTIF' | 'ARCHIVE';
  echeanceConservation?: string | null;
  /** Verrou (§12.8). */
  verrouMotif?: string | null;
  verrouLe?: string | null;
}

export type Confidentialite = 'PUBLIC' | 'PRIVE' | 'CONFIDENTIEL';

/** Libellés des niveaux de confidentialité. */
export const NIVEAUX_CONFIDENTIALITE: { valeur: Confidentialite; libelle: string }[] = [
  { valeur: 'PUBLIC', libelle: 'Public' },
  { valeur: 'PRIVE', libelle: 'Privé (déposant et archivistes)' },
  { valeur: 'CONFIDENTIEL', libelle: 'Confidentiel (personnes désignées)' },
];

/** Etiquette apposee a un document, avec sa couleur. */
export interface Tag {
  id: string;
  tag: string;
  couleur: string;
}

/** Une version du fichier ; une seule est courante. */
export interface Version {
  id: string;
  fileName: string;
  observation: string | null;
  principale: boolean;
  sizeLabel: string;
  createdAt: string;
  /** Numéro de versement (1, 2, …) et empreinte SHA-256 (§12.8). */
  numero?: number;
  empreinte?: string | null;
}

/** Corps envoye pour modifier la fiche d'un document. */
export interface DocumentRequest {
  name?: string;
  typeDocumentId?: string;
  expirationDate?: string | null;
  active?: boolean;
  etiquetteIds?: string[];
  confidentialite?: Confidentialite;
  objet?: string | null;
  dateDocument?: string | null;
}

export interface PageResult<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface SelectOption {
  id: string;
  name: string;
}
