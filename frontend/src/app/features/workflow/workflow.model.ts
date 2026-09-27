/** Enveloppe de pagination renvoyée par l'API. */
export interface PageResult<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

/** Une règle de workflow telle que renvoyée par l'API. */
export interface Workflow {
  id: string;
  name: string;
  steps: WorkflowStep[];
  workspaces: string[];
}

/**
 * Un validateur de la règle (§12.8) : NOMMÉ (une personne) ou désigné PAR
 * RÔLE sur un périmètre (à défaut, l'emplacement du document), résolu au
 * moment de la décision. Tous décident en même temps : `stepOrder` n'est
 * qu'un ordre d'affichage (D7).
 */
export interface WorkflowStep {
  id?: string;
  employeId: string | null;
  employeFullName?: string;
  roleId?: string | null;
  roleCode?: string | null;
  perimetreNoeudId?: string | null;
  label: string;
  stepOrder: number;
}

/** Corps envoyé pour créer / modifier une règle. */
export interface WorkflowRequest {
  name: string;
  steps: WorkflowStepRequest[];
}

export interface WorkflowStepRequest {
  employeId: string | null;
  roleId: string | null;
  perimetreNoeudId: string | null;
  label: string;
  stepOrder: number;
}
