export interface Ref {
  id: number;
  label: string;
}

/** Les 8 droits fins d'un groupe d'accès. */
export interface GedRights {
  access: boolean;
  lecture: boolean;
  modifier: boolean;
  uploader: boolean;
  supprimer: boolean;
  deplacer: boolean;
  ajouterVersion: boolean;
  verrouillerDeverrouiller: boolean;
}

/** Un groupe d'accès tel que renvoyé par l'API. */
export interface AccessGroup {
  id: number;
  code: string;
  name: string;
  rights: GedRights;
  workspaces: Ref[];
  users: Ref[];
  workspacesCount: number;
  usersCount: number;
}

/** Corps envoyé pour créer / modifier un groupe. */
export interface AccessGroupRequest {
  code: string;
  name: string;
  rights: GedRights;
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

/** Ordre et libellés des droits (fidèles au module GED de CCISTTA). */
export const RIGHT_KEYS: { key: keyof GedRights; label: string }[] = [
  { key: 'access', label: "Droits d'accès" },
  { key: 'lecture', label: 'Lecture' },
  { key: 'modifier', label: 'Modifier' },
  { key: 'uploader', label: 'Uploadé' },
  { key: 'supprimer', label: 'Supprimer' },
  { key: 'deplacer', label: 'Déplacer' },
  { key: 'ajouterVersion', label: 'Ajouter une version' },
  { key: 'verrouillerDeverrouiller', label: 'Verrouiller/Déverrouiller' },
];

export const EMPTY_RIGHTS = (): GedRights => ({
  access: false, lecture: false, modifier: false, uploader: false,
  supprimer: false, deplacer: false, ajouterVersion: false, verrouillerDeverrouiller: false,
});
