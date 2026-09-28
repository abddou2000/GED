/**
 * Workflow de validation (§12.8, contrat d'API E8) : un circuit figé au dépôt,
 * des validateurs sollicités EN MÊME TEMPS (aucune étape, aucun ordre — D7),
 * des décisions sur la version courante du document.
 */

export type StatutCircuit = 'EN_COURS' | 'VALIDE' | 'REFUSE' | 'ANNULE';
export type EtatValidateur = 'EN_ATTENTE' | 'VALIDE' | 'REFUSE';
export type TypeDecision = 'VALIDE' | 'REFUSE' | 'ANNULEE';

export interface ValidateurCircuit {
  id: string;
  /** NOMME (une personne) ou ROLE (quiconque détient le rôle sur le périmètre). */
  type: 'NOMME' | 'ROLE';
  employeId: string | null;
  employe: string | null;
  roleCode: string | null;
  perimetreNoeudId: string | null;
  libelle: string;
  etat: EtatValidateur;
  derniereDecision: string | null;
  reaffecteDe: string | null;
  reaffectePar: string | null;
  reaffecteLe: string | null;
  motifReaffectation: string | null;
}

export interface DecisionCircuit {
  id: string;
  validateurId: string;
  versionId: string | null;
  versionNumero: number | null;
  decision: TypeDecision;
  motif: string | null;
  auteur: string | null;
  applicationId: string | null;
  le: string;
  /** Portée sur une version antérieure : ne compte plus. */
  caduque: boolean;
}

export interface Circuit {
  id: string;
  documentId: string;
  document: string;
  statut: StatutCircuit;
  regleId: string | null;
  regle: string | null;
  initiateur: string | null;
  ouvertLe: string;
  closLe: string | null;
  annulePar: string | null;
  annuleLe: string | null;
  motifAnnulation: string | null;
  versionCouranteId: string | null;
  versionCouranteNumero: number | null;
  validateurs: ValidateurCircuit[];
  decisions: DecisionCircuit[];
  peutDecider: boolean;
  peutAnnuler: boolean;
}

/** Ligne de « à traiter ». */
export interface ATraiter {
  circuitId: string;
  documentId: string;
  document: string;
  validateurId: string;
  libelle: string;
  type: 'NOMME' | 'ROLE';
  ouvertLe: string;
  initiateur: string | null;
  versionNumero: number | null;
}

/** Décision rendue par la personne connectée. */
export interface DecisionRendue {
  id: string;
  circuitId: string;
  documentId: string;
  document: string;
  libelle: string;
  decision: TypeDecision;
  motif: string | null;
  versionNumero: number | null;
  le: string;
  statutCircuit: StatutCircuit;
}

/** Validateur qui ne peut pas décider (tableau de bord de l'Administrateur, D1). */
export interface Anomalie {
  circuitId: string;
  documentId: string;
  document: string;
  validateurId: string;
  libelle: string;
  employeId: string | null;
  employe: string | null;
  roleCode: string | null;
  anomalie: 'SANS_IDENTITE' | 'SANS_DROIT' | 'INACTIF' | 'AUCUN_PORTEUR';
  depuis: string;
}

export interface RegleDocument {
  regleId: string;
  name: string;
  origine: 'TYPE' | 'NOEUD';
  origineId: string;
}

export interface PageATraiter {
  content: ATraiter[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export const LIBELLE_STATUT: Record<StatutCircuit, string> = {
  EN_COURS: 'En cours', VALIDE: 'Validé', REFUSE: 'Refusé', ANNULE: 'Annulé',
};

export const LIBELLE_ANOMALIE: Record<Anomalie['anomalie'], string> = {
  SANS_IDENTITE: 'Jamais connecté à la GED',
  SANS_DROIT: 'Plus de droit Valider',
  INACTIF: 'Inactif depuis longtemps',
  AUCUN_PORTEUR: 'Rôle détenu par personne',
};
