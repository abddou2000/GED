export interface Ref {
  id: string;
  label: string;
}

/** Un plan d'indexation tel que renvoyé par l'API. */
export interface PlanIndexation {
  id: string;
  code: string;
  nomDuPlan: string;
  modeIndexation: boolean;
  manuel: boolean;
  majuscule: boolean;
  separateur: string;
  indices: Ref[];
  indexCount: number;
  /** Jetons du nom composé, dans l'ordre : id d'index ou clé système. */
  charteIds: string[];
  /** Charte telle qu'enregistrée ; du texte libre pour un plan hérité. */
  charteNommage: string | null;
  preview: string;
}

/** Un jeton de la charte : soit un index du plan, soit un jeton système. */
export interface Jeton {
  id: string;
  name: string;
  systeme: boolean;
}

/** Corps envoyé pour créer / modifier un plan. */
export interface PlanIndexationRequest {
  code: string;
  nomDuPlan: string;
  modeIndexation: boolean;
  manuel: boolean;
  majuscule: boolean;
  separateur: string;
  indexIds: string[];
  charteIds: string[];
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
