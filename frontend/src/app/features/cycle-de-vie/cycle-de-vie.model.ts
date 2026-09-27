/** Issue de l'archivage d'un document (§12.6). */
export type IssueArchivage = 'ARCHIVE' | 'ANOMALIE' | 'DEJA_ARCHIVE' | 'IGNORE' | 'ECHEC';

export interface ResultatArchivage {
  documentId: string;
  issue: IssueArchivage;
  /** VALIDE ou ECHEC : copie de conservation PDF/A-2. */
  copieConservation: string | null;
  motif: string | null;
}

/** Copie de conservation PDF/A-2 de la version courante (§6.1.4). */
export interface CopieConservation {
  statut: 'VALIDE' | 'ECHEC';
  methode: string | null;
  format: string | null;
  motif: string | null;
  empreinte: string | null;
  tailleOctets: number | null;
  creeLe: string | null;
}

export interface Conservation {
  documentId: string;
  statutConservation: 'ACTIF' | 'ARCHIVE';
  archiveLe: string | null;
  archivePar: string | null;
  versionId: string | null;
  copie: CopieConservation | null;
}

export type EtatJob = 'EN_ATTENTE' | 'EN_COURS' | 'TERMINE' | 'ANNULE';

/** Archivage d'un dossier entier (D10), traité par tranches de 100. */
export interface JobArchivage {
  id: string;
  dossierId: string;
  dossierNom: string;
  demandeurId: string | null;
  etat: EtatJob;
  total: number;
  traites: number;
  archives: number;
  anomalies: number;
  echecs: number;
  annulationDemandee: boolean;
  creeLe: string;
  demarreLe: string | null;
  termineLe: string | null;
}

export interface ElementJob {
  documentId: string;
  nom: string;
  rang: number;
  resultat: IssueArchivage | null;
  motif: string | null;
  traiteLe: string | null;
}

/** Export de dossier en traitement de fond (§12.10). */
export interface ExportDossier {
  id: string;
  dossierId: string;
  dossierNom: string;
  etat: 'EN_ATTENTE' | 'EN_COURS' | 'TERMINE' | 'ECHEC' | 'EXPIRE';
  nbDocuments: number;
  tailleEstimee: number;
  tailleOctets: number | null;
  motif: string | null;
  creeLe: string;
  termineLe: string | null;
  expireLe: string | null;
}

/** Réponse d'une demande d'export : l'archive tout de suite, ou un export de fond. */
export type IssueExport = { archive: Blob } | { differe: ExportDossier };
