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
  /**
   * Membres en attente de première connexion (T-025) : fiches employé sans
   * identité GED, présentes aussi dans `users`. Elles n'ont aucun droit tant que
   * la personne ne s'est pas connectée ; l'écran les renvoie dans `userIds`
   * pour les conserver.
   */
  pendingUserIds?: string[];
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
