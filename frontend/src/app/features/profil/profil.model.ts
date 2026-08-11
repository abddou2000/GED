export interface Ref {
  id: number;
  label: string;
}

/**
 * Fiche de profil : identité et activité réelle dans la GED.
 *
 * <p>Tous les champs viennent de données existantes. Rien n'est estimé : un
 * profil qui afficherait des chiffres décoratifs tromperait sur les
 * responsabilités réelles d'une personne.
 */
export interface Profil {
  id: number;
  fullName: string;
  firstName: string;
  lastName: string;
  hasUser: boolean;
  /** Adresse de connexion ; `null` si l'employé n'a pas de compte. */
  email: string | null;
  compteActif: boolean;
  /** Dernière connexion réussie ; `null` si jamais connecté. */
  derniereConnexion: string | null;
  espacesProprietaire: Ref[];
  groupesAcces: Ref[];
  documentsDeposes: number;
  signaturesEnAttente: number;
  signaturesTraitees: number;
}
