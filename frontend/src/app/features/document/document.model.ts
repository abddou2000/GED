export interface Ref {
  id: string;
  label: string;
}

/** Un document déposé tel que renvoyé par l'API. */
export interface DocumentItem {
  id: string;
  name: string;
  workspace: Ref | null;
  typeDocument: Ref | null;
  fileName: string;
  extension: string;
  sizeKo: number;
  sizeLabel: string;
  expirationDate: string | null;
  active: boolean;
  verrouille: boolean;
  /** Rangement lisible : dossier / type. */
  chemin: string;
  createdBy: string | null;
  etiquettes: Tag[];
  versions: Version[];
  createdAt: string;
  /**
   * Traitement OCR de la version courante : EN_ATTENTE_OCR / EN_COURS_OCR
   * (contenu pas encore interrogeable), OCR_TERMINE, OCR_ECHEC (« contenu non
   * interrogeable ») ; null si le format n'a pas de contenu textuel.
   */
  statutOcr?: 'EN_ATTENTE_OCR' | 'EN_COURS_OCR' | 'OCR_TERMINE' | 'OCR_ECHEC' | null;
  /**
   * Issue de l'indexation (dépôt en deux temps, §12.11) : INDEXE, SANS_PLAN, ou
   * A_INDEXER (métadonnées à saisir ou à reprendre).
   */
  statutIndexation?: 'INDEXE' | 'SANS_PLAN' | 'A_INDEXER' | null;
  /** Pourquoi le dépôt n'a pas pu enregistrer les métadonnées (réponse du dépôt seulement). */
  motifIndexation?: string | null;
  /** ACTIF, ou ARCHIVE : lecture seule totale (§12.6). */
  statutConservation?: 'ACTIF' | 'ARCHIVE' | null;
  archiveLe?: string | null;
  /** Canal du dépôt (T-040) : INTERFACE, API, BUREAU_ORDRE, REPRISE. */
  canalDepot?: CanalDepot | null;
  /** Application appelante (clé d'API), absente depuis l'interface. */
  applicationId?: string | null;
  /** Identité GED du déposant (déposant délégué si depotDelegue). */
  deposantUtilisateurId?: string | null;
  depotDelegue?: boolean;
  /** En corbeille. */
  supprime?: boolean;
  /** Niveau de confidentialité (§12.3). */
  confidentialite?: Confidentialite;
  /**
   * Permissions de l'appelant sur ce document (fiche seulement) : l'interface
   * masque les actions qu'il ne peut pas exercer. Confort : le serveur décide.
   */
  permissions?: string[] | null;
  /** Emplacements complémentaires visibles (fiche seulement). */
  rattachements?: Ref[] | null;
  /** Socle commun (§12.7). */
  objet?: string | null;
  dateDocument?: string | null;
  metadonnees?: Record<string, unknown>;
  /** Échéance de conservation (§12.9), calculée par la base. */
  echeanceConservation?: string | null;
  /** Échéance atteinte : document à examiner par l'Agent d'archive (aucune suppression automatique). */
  echeanceDepassee?: boolean;
  /** Verrou (§12.8). */
  verrouMotif?: string | null;
  verrouLe?: string | null;
}

export type CanalDepot = 'INTERFACE' | 'API' | 'BUREAU_ORDRE' | 'REPRISE';

/** Libellés des canaux de dépôt (T-040). */
export const LIBELLES_CANAL: Record<CanalDepot, string> = {
  INTERFACE: 'Interface web',
  API: 'Application (API)',
  BUREAU_ORDRE: "Bureau d'ordre",
  REPRISE: 'Reprise',
};

export type Confidentialite = 'PUBLIC' | 'PRIVE' | 'CONFIDENTIEL';

/** Libellés des niveaux de confidentialité. */
export const NIVEAUX_CONFIDENTIALITE: { valeur: Confidentialite; libelle: string }[] = [
  { valeur: 'PUBLIC', libelle: 'Public' },
  { valeur: 'PRIVE', libelle: 'Privé (déposant et archivistes)' },
  { valeur: 'CONFIDENTIEL', libelle: 'Confidentiel (personnes désignées)' },
];

/** Etiquette apposee a un document, avec sa couleur. */
export interface Tag {
  id: string;
  tag: string;
  couleur: string;
}

/** Une version du fichier ; une seule est courante. */
export interface Version {
  id: string;
  fileName: string;
  observation: string | null;
  principale: boolean;
  sizeLabel: string;
  createdAt: string;
  /** Numéro de versement (1, 2, …) (§12.8). */
  numero?: number;
  /** Type réel détecté au dépôt (Tika). */
  typeMime?: string | null;
  /** SHA-256 du contenu, vérifié chaque mois. */
  empreinte?: string | null;
  /** Identité GED de l'auteur du versement (ANO-F-012). */
  auteurId?: string | null;
}

/** Corps envoye pour modifier la fiche d'un document. */
export interface DocumentRequest {
  name?: string;
  typeDocumentId?: string;
  expirationDate?: string | null;
  active?: boolean;
  etiquetteIds?: string[];
  confidentialite?: Confidentialite;
  objet?: string | null;
  dateDocument?: string | null;
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

/* ---------- Recherche sur métadonnées (POST /documents/recherche, ANO-F-010) ---------- */

/** Un critère d'index : `valeur` (texte, liste, booléen) ou bornes `de` / `a` (date, nombre). */
export interface CritereIndexRecherche {
  code: string;
  valeur?: string | null;
  de?: string | null;
  a?: string | null;
}

/**
 * Corps de la recherche multicritère ; les critères absents ne filtrent pas,
 * les présents se combinent en ET. `dateDocumentDu` / `dateDocumentAu`,
 * `confidentialite` et `deposantUtilisateurId` sont les critères imposés
 * ajoutés à l'API au tour 2 (ANO-F-011).
 */
export interface RequeteRecherche {
  texte?: string | null;
  typeDocumentId?: string | null;
  noeudId?: string | null;
  criteres?: CritereIndexRecherche[];
  /** ACTIF ou ARCHIVE ; absent = les deux. */
  statutConservation?: 'ACTIF' | 'ARCHIVE' | null;
  echeanceDepassee?: boolean | null;
  dateDocumentDu?: string | null;
  dateDocumentAu?: string | null;
  confidentialite?: Confidentialite | null;
  deposantUtilisateurId?: string | null;
  page?: number;
  size?: number;
}

/** État OCR lisible dans une liste (ANO-F-017) ; `null` : rien à signaler. */
export interface EtatOcrAffiche {
  libelle: string;
  info: string;
  echec: boolean;
}

/**
 * Un document dont le contenu n'est pas (encore) interrogeable doit se voir
 * dans les listes, pas seulement sur sa fiche : sinon celui qui ne le trouve
 * pas en recherche plein texte ignore pourquoi (ANO-F-017).
 */
export function etatOcr(statut: DocumentItem['statutOcr']): EtatOcrAffiche | null {
  switch (statut) {
    case 'EN_ATTENTE_OCR':
    case 'EN_COURS_OCR':
      return { libelle: 'OCR en attente', echec: false,
        info: 'Contenu pas encore interrogeable en recherche plein texte : traitement OCR en attente.' };
    case 'OCR_ECHEC':
      return { libelle: 'OCR en échec', echec: true,
        info: 'Contenu non interrogeable en recherche plein texte : le traitement OCR a échoué.' };
    default:
      return null;
  }
}
