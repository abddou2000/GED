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
}

/** Corps envoyé pour créer / modifier un dossier. */
export interface WorkSpaceRequest {
  name: string;
  code: string;
  description?: string | null;
  status: WorkspaceStatus;
  employeId: string;
  parentId?: string | null;
  workflowId: string;
}

/** Nœud de l'arborescence (vue Arbre). */
export interface TreeNode {
  id: string;
  name: string;
  status: string;
  parentId: string | null;
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
