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
  /**
   * Traitement OCR de la version courante : EN_ATTENTE_OCR / EN_COURS_OCR
   * (contenu pas encore interrogeable), OCR_TERMINE, OCR_ECHEC (« contenu non
   * interrogeable ») ; null si le format n'a pas de contenu textuel.
   */
  statutOcr?: 'EN_ATTENTE_OCR' | 'EN_COURS_OCR' | 'OCR_TERMINE' | 'OCR_ECHEC' | null;
}

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
  /** Type réel détecté au dépôt (Tika). */
  typeMime?: string | null;
  /** SHA-256 du contenu, vérifié chaque mois. */
  empreinte?: string | null;
}

/** Corps envoye pour modifier la fiche d'un document. */
export interface DocumentRequest {
  name?: string;
  typeDocumentId?: string;
  expirationDate?: string | null;
  active?: boolean;
  etiquetteIds?: string[];
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
