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

export interface WorkflowStep {
  id?: string;
  employeId: string | null;
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
  employeId: string;
  label: string;
  stepOrder: number;
}
