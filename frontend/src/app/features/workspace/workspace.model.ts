export type WorkspaceStatus = 'ACTIF' | 'INACTIF' | 'ARCHIVE';

export interface Ref {
  id: string;
  label: string;
}

/** Un espace de travail (dossier) tel que renvoyé par l'API. */
export interface WorkSpace {
  id: string;
  name: string;
  code: string;
  description: string | null;
  status: WorkspaceStatus;
  owner: Ref | null;
  parent: Ref | null;
  workflow: Ref | null;
  childrenCount: number;
  /** Groupes d'accès couvrant le dossier — utilisateurs rattachés à ce dossier. */
  accessGroups?: Ref[];
  /** ESPACE ou DOSSIER. */
  nature?: string;
  /** METIER ou ECHANGE (R-03, D12) ; celui de l'espace pour un dossier. */
  usageEspace?: 'METIER' | 'ECHANGE';
  /** Drapeau d'archivage du nœud (D10). */
  statutConservation?: 'ACTIF' | 'ARCHIVE';
}

/** Corps envoyé pour créer / modifier un dossier. */
export interface WorkSpaceRequest {
  name: string;
  code: string;
  description?: string | null;
  status: WorkspaceStatus;
  employeId: string;
  parentId?: string | null;
  workflowId: string | null;
  /** Usage d'un espace (ignoré pour un dossier). */
  usageEspace?: 'METIER' | 'ECHANGE';
}

/** Nœud de l'arborescence (vue Arbre). */
export interface TreeNode {
  id: string;
  name: string;
  /** Absent pour un nœud de passage. */
  status: string | null;
  parentId: string | null;
  /**
   * Nœud non couvert par une habilitation de l'utilisateur, montré seulement
   * parce qu'il mène à un nœud couvert (P5) : libellé seul, sans lien ni action.
   */
  passage?: boolean;
  children: TreeNode[];
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
