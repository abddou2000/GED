export interface Ref {
  id: number;
  label: string;
}

/** Un plan d'indexation tel que renvoyé par l'API. */
export interface PlanIndexation {
  id: number;
  code: string;
  nomDuPlan: string;
  modeIndexation: boolean;
  manuel: boolean;
  majuscule: boolean;
  separateur: string;
  indices: Ref[];
  indexCount: number;
  preview: string;
}

/** Corps envoyé pour créer / modifier un plan. */
export interface PlanIndexationRequest {
  code: string;
  nomDuPlan: string;
  modeIndexation: boolean;
  manuel: boolean;
  majuscule: boolean;
  separateur: string;
  indexIds: number[];
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
