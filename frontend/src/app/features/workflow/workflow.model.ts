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
  id: number;
  name: string;
  steps: WorkflowStep[];
  workspaces: string[];
}

export interface WorkflowStep {
  id?: number;
  employeId: number | null;
  employeFullName?: string;
  label: string;
  stepOrder: number;
}

/** Corps envoyé pour créer / modifier une règle. */
export interface WorkflowRequest {
  name: string;
  steps: WorkflowStepRequest[];
}

export interface WorkflowStepRequest {
  employeId: number;
  label: string;
  stepOrder: number;
}
