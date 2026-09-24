export interface Ref {
  id: number;
  label: string;
}

/** Un groupe d'accès tel que renvoyé par l'API. */
export interface AccessGroup {
  id: number;
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
  workspaceIds: number[];
  userIds: number[];
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
