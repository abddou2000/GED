export type WorkspaceStatus = 'ACTIF' | 'INACTIF' | 'ARCHIVE';

export interface Ref {
  id: number;
  label: string;
}

/** Un espace de travail (dossier) tel que renvoyé par l'API. */
export interface WorkSpace {
  id: number;
  name: string;
  code: string;
  description: string | null;
  status: WorkspaceStatus;
  owner: Ref | null;
  parent: Ref | null;
  workflow: Ref | null;
  childrenCount: number;
}

/** Corps envoyé pour créer / modifier un dossier. */
export interface WorkSpaceRequest {
  name: string;
  code: string;
  description?: string | null;
  status: WorkspaceStatus;
  employeId: number;
  parentId?: number | null;
  workflowId: number;
}

/** Nœud de l'arborescence (vue Arbre). */
export interface TreeNode {
  id: number;
  name: string;
  status: string;
  parentId: number | null;
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
  id: number;
  name: string;
}
