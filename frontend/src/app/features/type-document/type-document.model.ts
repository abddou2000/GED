export interface Ref {
  id: string;
  label: string;
}

/** Un type de document tel que renvoyé par l'API. */
export interface TypeDocument {
  id: string;
  code: string;
  typeDeDocument: string;
  description: string;
  workspace: Ref | null;
  planIndexation: Ref | null;
  typeAutorise: string[];
  tailleMaxMo: number;
  /** Conservation (§12.9) : durée en mois, point de départ. */
  dureeConservationMois?: number | null;
  pointDepart?: PointDepart;
  pointDepartIndexCode?: string | null;
  confidentialiteDefaut?: string;
  /** Un type utilisé ne se supprime pas, il se désactive (§12.7). */
  actif?: boolean;
  /** Version en vigueur du plan d'indexation. */
  versionPlan?: number | null;
  /** Règle de workflow du type (§12.8) : prioritaire sur celle des dossiers. */
  regleWorkflow?: Ref | null;
}

/**
 * Re-typologisation d'un lot (§12.7, `POST /type-documents/retypages`) : les
 * documents du type source (tous, ou la sélection) passent au type cible ; la
 * correspondance renomme un champ (code source → code cible), un champ de même
 * code est conservé, un champ sans équivalent est perdu (signalé au rapport).
 */
export interface DemandeRetypage {
  sourceTypeDocumentId: string;
  cibleTypeDocumentId: string;
  correspondance: Record<string, string>;
  /** Vide ou absent : tous les documents vivants du type source. */
  documentIds?: string[];
}

export type StatutRetypage = 'EN_ATTENTE' | 'EN_COURS' | 'TERMINE' | 'ECHEC';

/** Une ligne du rapport : un document traité. */
export interface LigneRetypage {
  documentId: string;
  nom?: string;
  resultat: 'SUCCES' | 'ECHEC' | string;
  motif?: string;
  champsPerdus?: string[];
}

/** Travail de re-typologisation (traitement de fond), suivi par `GET /retypages/{id}`. */
export interface JobRetypage {
  id: string;
  sourceTypeDocumentId: string;
  cibleTypeDocumentId: string;
  correspondance: Record<string, string>;
  statut: StatutRetypage;
  total: number;
  traites: number;
  reussis: number;
  echecs: number;
  rapport: LigneRetypage[];
  creeLe: string;
  debutLe?: string | null;
  finLe?: string | null;
}

export const LIBELLES_STATUT_RETYPAGE: Record<StatutRetypage, string> = {
  EN_ATTENTE: 'En attente',
  EN_COURS: 'En cours',
  TERMINE: 'Terminé',
  ECHEC: 'En échec',
};

export type PointDepart = 'DATE_DOCUMENT' | 'DATE_DEPOT' | 'METADONNEE';

export const POINTS_DEPART: { valeur: PointDepart; libelle: string }[] = [
  { valeur: 'DATE_DOCUMENT', libelle: 'Date du document' },
  { valeur: 'DATE_DEPOT', libelle: 'Date de dépôt' },
  { valeur: 'METADONNEE', libelle: 'Une date du plan d\'indexation' },
];

/** Corps envoyé pour créer / modifier un type de document. */
export interface TypeDocumentRequest {
  code: string;
  typeDeDocument: string;
  description: string;
  workspaceId: string;
  planIndexationId?: string | null;
  typeAutorise: string[];
  tailleMaxMo: number;
  dureeConservationMois?: number | null;
  pointDepart?: PointDepart;
  pointDepartIndexCode?: string | null;
  confidentialiteDefaut?: string | null;
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
