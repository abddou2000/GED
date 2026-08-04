export interface Ref {
  id: number;
  label: string;
}

export type SignatureStatus = 'PENDING' | 'SIGNED' | 'REJECTED';

/** Une demande de signature telle que renvoyée par l'API. */
export interface Signature {
  id: number;
  document: Ref | null;
  type: string | null;
  workspace: string | null;
  approver: string | null;
  stepLabel: string;
  stepOrder: number;
  status: SignatureStatus;
  signedAt: string | null;
  motif: string | null;
  documentActive: boolean;
}
