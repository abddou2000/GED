export interface Ref {
  id: number;
  label: string;
}

/** Un type de document tel que renvoyé par l'API. */
export interface TypeDocument {
  id: number;
  code: string;
  typeDeDocument: string;
  description: string;
  workspace: Ref | null;
  planIndexation: Ref | null;
  typeAutorise: string[];
  tailleMaxMo: number;
}

/** Corps envoyé pour créer / modifier un type de document. */
export interface TypeDocumentRequest {
  code: string;
  typeDeDocument: string;
  description: string;
  workspaceId: number;
  planIndexationId?: number | null;
  typeAutorise: string[];
  tailleMaxMo: number;
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
  /** Nom du plan d'indexation, ou null : un type sans plan ne porte aucun index. */
  plan?: string | null;
  /** Extensions acceptées, en minuscules. Vide = le type n'impose rien. */
  formats?: string[];
  /** Taille maximale d'un fichier de ce type, en mégaoctets. */
  tailleMaxMo?: number;
  /**
   * Le plan compose le nom du document depuis les index (charte « Auto »).
   * Le nom saisi au dépôt serait alors remplacé à la confirmation de
   * l'indexation : le formulaire n'a pas à le demander.
   */
  charteAuto?: boolean;
}

/** Formats de fichier autorisés (fidèles à CCISTTA). */
export const FILE_TYPES: { value: string; label: string }[] = [
  { value: 'pdf', label: 'PDF' },
  { value: 'docx', label: 'DOCX' },
  { value: 'doc', label: 'DOC' },
  { value: 'xlsx', label: 'XLSX' },
  { value: 'xls', label: 'XLS' },
];
