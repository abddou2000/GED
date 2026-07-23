/** Enveloppe de pagination renvoyée par l'API. */
export interface PageResult<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

/** Un circuit de validation tel que renvoyé par l'API. */
export interface Workflow {
  id: number;
  name: string;
  status: string;            // ACTIVE | DRAFT
  espaceDeTravail: string | null;
  lastModified: string | null;
  steps: WorkflowStep[];
  workspaces: string[];
  archived?: boolean;   // marqueur client (onglet Archivées)
}

export interface WorkflowStep {
  id?: number;
  employeId: number | null;
  employeFullName?: string;
  label: string;
  stepOrder: number;
}

/** Corps envoyé pour créer / modifier un circuit. */
export interface WorkflowRequest {
  name: string;
  steps: WorkflowStepRequest[];
}

export interface WorkflowStepRequest {
  employeId: number;
  label: string;
  stepOrder: number;
}
