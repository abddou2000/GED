/**
 * Titre de la barre supérieure, par chemin de route (ANO-F-035).
 *
 * <p>La table était indexée par le seul premier segment d'URL : les écrans
 * d'administration (`administration/…`) et ceux ajoutés depuis (recherches,
 * exports, traitements OCR) retombaient sur « Accueil ». Elle l'est désormais
 * par chemin, le plus long préfixe l'emportant ; `titres.spec.ts` vérifie
 * que chaque route de l'application y a son titre.
 */
const TITRES: Record<string, string> = {
  'accueil': 'Accueil',
  'televerser': 'Documents déposés',
  'mes-workflow': 'Mes validations',
  'espaces-de-travail': 'Espaces de travail',
  'recherche-par-index': 'Recherche par index',
  'recherche': 'Recherche plein texte',
  'mes-exports': 'Mes exports',
  'regles-de-workflow': 'Règles de Workflow',
  'groupe-d-acces': "Groupe d'accès",
  'index': 'Index',
  'plan-indexation': "Plan d'indexation",
  'type-de-document': 'Type de document',
  'type-de-document/retypage': 'Re-typologie en lot',
  'etiquette': 'Étiquette',
  'traitements-ocr': 'Traitements OCR',
  'administration/habilitations': 'Habilitations',
  'administration/roles': 'Rôles',
  'administration/droits-effectifs': 'Droits effectifs',
  'administration/sessions': 'Sessions',
  'journal-audit': "Journal d'audit",
  'cles-api': "Clés d'API",
  'notifications': 'Notifications',
  'profil': 'Mon profil',
};

/** Titre de la page pour une URL du routeur (`/a/b?x#y`) ; « Accueil » si aucune entrée. */
export function titrePage(url: string): string {
  const segments = url.split(/[?#]/)[0].split('/').filter(Boolean);
  for (let n = segments.length; n > 0; n--) {
    const titre = TITRES[segments.slice(0, n).join('/')];
    if (titre) return titre;
  }
  return 'Accueil';
}
