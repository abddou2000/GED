export interface Ref {
  id: number;
  label: string;
}

/** Un document déposé tel que renvoyé par l'API. */
export interface DocumentItem {
  id: number;
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
}

/** Etiquette apposee a un document, avec sa couleur. */
export interface Tag {
  id: number;
  tag: string;
  couleur: string;
}

/** Une version du fichier ; une seule est courante. */
export interface Version {
  id: number;
  fileName: string;
  observation: string | null;
  principale: boolean;
  sizeLabel: string;
  createdAt: string;
}

/** Corps envoye pour modifier la fiche d'un document. */
export interface DocumentRequest {
  name?: string;
  typeDocumentId?: number;
  expirationDate?: string | null;
  active?: boolean;
  etiquetteIds?: number[];
}

export interface PageResult<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface SelectOption {
  id: number;
  name: string;
}
