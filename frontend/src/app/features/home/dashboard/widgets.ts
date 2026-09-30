/**
 * Catalogue des encarts du tableau de bord.
 *
 * Un seul endroit décrit ce qui EXISTE ; les préférences, elles, ne décrivent
 * que ce que l'utilisateur en fait (visible ou non, dans quel ordre). Ajouter un
 * encart au produit se fait donc ici et dans le gabarit d'accueil, sans toucher
 * à la persistance.
 */
import { CodeModule } from '../../../core/modules.service';

export type CleWidget =
  | 'indicateurs'
  | 'a-valider'
  | 'raccourcis'
  | 'documents-recents'
  | 'activite'
  | 'espaces'
  | 'repartition'
  | 'depots';

export interface DefinitionWidget {
  cle: CleWidget;
  titre: string;
  /** Ce que l'encart apporte — affiché dans le panneau de personnalisation. */
  resume: string;
  icone: string;
  /** Largeur en colonnes sur la grille de 12. */
  colonnes: 4 | 6 | 8 | 12;
  /**
   * Encart obligatoire : il ne peut être ni masqué ni déplacé. Réservé à la
   * rangée d'indicateurs, qui sert de repère haut de page.
   */
  fixe?: boolean;
  /**
   * Module métier dont l'encart dépend (T-088) : masqué, et absent du panneau
   * de personnalisation, quand ce module est désactivé sur l'environnement.
   */
  module?: CodeModule;
}

export const CATALOGUE: readonly DefinitionWidget[] = [
  {
    cle: 'indicateurs', titre: 'Indicateurs', icone: 'dashboard', colonnes: 12, fixe: true,
    resume: 'Dossiers, documents, signatures en attente et groupes d’accès.',
  },
  {
    cle: 'a-valider', titre: 'À valider', icone: 'nav-mesworkflow', colonnes: 8, module: 'workflow',
    resume: 'Les documents qui attendent votre signature.',
  },
  {
    cle: 'raccourcis', titre: 'Raccourcis', icone: 'nav-workflow', colonnes: 4,
    resume: 'Les actions que vous lancez le plus souvent.',
  },
  {
    cle: 'documents-recents', titre: 'Documents récents', icone: 'clock', colonnes: 8,
    resume: 'Les derniers documents déposés dans l’application.',
  },
  {
    cle: 'activite', titre: 'Activité récente', icone: 'sign', colonnes: 4, module: 'workflow',
    resume: 'Vos dernières signatures et vos derniers refus.',
  },
  {
    cle: 'espaces', titre: 'Espaces de travail', icone: 'nav-workspaces', colonnes: 4,
    resume: 'Vos dossiers, avec leur nombre de documents.',
  },
  {
    cle: 'repartition', titre: 'Répartition par type', icone: 'chart', colonnes: 4,
    resume: 'Comment les documents se répartissent entre les types.',
  },
  {
    cle: 'depots', titre: 'Dépôts sur 30 jours', icone: 'trending', colonnes: 8,
    resume: 'Le volume de dépôts jour par jour sur le dernier mois.',
  },
] as const;

/**
 * Disposition servie au premier affichage, avant toute personnalisation.
 *
 * <p>Trois encarts, pas plus : ainsi l'accueil tient dans l'écran sans le
 * moindre défilement. Une seconde rangée y ajoute environ 250 px, et la page
 * se met à défiler dès l'ouverture — c'est exactement le reproche fait à
 * l'ancien tableau de bord. Les autres encarts existent et s'ajoutent en un
 * clic ; en descendre le contenu est alors une décision de l'utilisateur, pas
 * un défaut de conception.</p>
 */
export const DISPOSITION_PAR_DEFAUT: readonly CleWidget[] = [
  'indicateurs', 'a-valider', 'raccourcis',
];

export const definition = (cle: CleWidget): DefinitionWidget | undefined =>
  CATALOGUE.find(d => d.cle === cle);
