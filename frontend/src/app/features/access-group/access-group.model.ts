export interface Ref {
  id: string;
  label: string;
}

/** Un groupe d'accès tel que renvoyé par l'API. */
export interface AccessGroup {
  id: string;
  code: string;
  name: string;
  workspaces: Ref[];
  users: Ref[];
  workspacesCount: number;
  usersCount: number;
}

/** Corps envoyé pour créer / modifier un groupe. */
export interface AccessGroupRequest {
  code: string;
  name: string;
  workspaceIds: string[];
  userIds: string[];
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
