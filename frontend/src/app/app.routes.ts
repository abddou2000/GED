import { Routes } from '@angular/router';
import { administrateurGuard, authGuard, permissionGuard, roleGuard } from './core/auth.guard';
import { moduleGuard } from './core/module.guard';

/**
 * Routing GED.
 *  - `/login` : écran de connexion, HORS de la coque applicative.
 *  - Tout le reste : enfants de la coque `Shell` (barre latérale + barre supérieure).
 *  - Route par défaut `''` → `/accueil` (tableau de bord), plus jamais une table de config.
 * Chaque écran interne est chargé à la demande (lazy loading).
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login/login').then(m => m.Login),
  },
  {
    path: '',
    // Toute la coque est derrière la garde : aucun écran interne ne s'ouvre
    // sans session valide, et l'adresse demandée est retenue pour y revenir
    // après connexion.
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell/shell').then(m => m.Shell),
    children: [
      { path: '', redirectTo: 'accueil', pathMatch: 'full' },
      {
        path: 'accueil',
        loadComponent: () => import('./features/home/dashboard/dashboard').then(m => m.Dashboard),
      },
      {
        // Tout le reste exige au moins un rôle GED (une identité sans rôle
        // reste sur l'accueil vide, §3.4.2). Les écrans d'un module métier
        // désactivé par configuration sont fermés (moduleGuard, T-088).
        path: '',
        canActivateChild: [roleGuard],
        children: [
      /* Écrans d'administration (ANO-F-003) : chacun est gardé par la
         permission qui fait paraître son menu. Confort seulement : le serveur
         refuse les écritures (GardeDroitsRequetes) ; les lectures des
         référentiels restent ouvertes, le dépôt et l'indexation en ont besoin. */
      {
        path: 'regles-de-workflow',
        canActivate: [permissionGuard('GERER_REFERENTIELS'), moduleGuard('workflow')],
        loadComponent: () => import('./features/workflow/workflow-list/workflow-list').then(m => m.WorkflowList),
      },
      {
        path: 'espaces-de-travail',
        loadComponent: () => import('./features/workspace/workspace-list/workspace-list').then(m => m.WorkspaceList),
      },
      {
        // Fiche d'un dossier : contenu, sous-dossiers, documents.
        path: 'espaces-de-travail/:id',
        loadComponent: () => import('./features/workspace/workspace-detail/workspace-detail').then(m => m.WorkspaceDetail),
      },
      {
        path: 'groupe-d-acces',
        canActivate: [permissionGuard('GERER_ROLES_HABILITATIONS')],
        loadComponent: () => import('./features/access-group/access-group-list/access-group-list').then(m => m.AccessGroupList),
      },
      {
        // Fiche d'un groupe : membres et espaces couverts.
        path: 'groupe-d-acces/:id',
        canActivate: [permissionGuard('GERER_ROLES_HABILITATIONS')],
        loadComponent: () => import('./features/access-group/access-group-detail/access-group-detail').then(m => m.AccessGroupDetail),
      },
      {
        path: 'index',
        canActivate: [permissionGuard('GERER_REFERENTIELS')],
        loadComponent: () => import('./features/index/index-list/index-list').then(m => m.IndexList),
      },
      {
        path: 'plan-indexation',
        canActivate: [permissionGuard('GERER_REFERENTIELS')],
        loadComponent: () => import('./features/plan-indexation/plan-indexation-list/plan-indexation-list').then(m => m.PlanIndexationList),
      },
      {
        // Création et édition sur pages dédiées, comme l'application d'origine.
        path: 'plan-indexation/create',
        canActivate: [permissionGuard('GERER_REFERENTIELS')],
        loadComponent: () => import('./features/plan-indexation/plan-indexation-form/plan-indexation-form').then(m => m.PlanIndexationForm),
      },
      {
        path: 'plan-indexation/:id/edit',
        canActivate: [permissionGuard('GERER_REFERENTIELS')],
        loadComponent: () => import('./features/plan-indexation/plan-indexation-form/plan-indexation-form').then(m => m.PlanIndexationForm),
      },
      {
        path: 'type-de-document',
        canActivate: [permissionGuard('GERER_REFERENTIELS')],
        loadComponent: () => import('./features/type-document/type-document-list/type-document-list').then(m => m.TypeDocumentList),
      },
      {
        // Re-typologie en lot (§12.7, ANO-F-015) : module « cycle de vie ».
        // Déclarée avant la fiche, sinon « retypage » serait lu comme un identifiant.
        path: 'type-de-document/retypage',
        canActivate: [permissionGuard('GERER_REFERENTIELS'), moduleGuard('cycledevie')],
        loadComponent: () => import('./features/type-document/retypage-lot/retypage-lot').then(m => m.RetypageLot),
      },
      {
        // Fiche d'un type : code, espace, formats, taille, plan, description.
        path: 'type-de-document/:id',
        canActivate: [permissionGuard('GERER_REFERENTIELS')],
        loadComponent: () => import('./features/type-document/type-document-detail/type-document-detail').then(m => m.TypeDocumentDetail),
      },
      {
        path: 'etiquette',
        canActivate: [permissionGuard('GERER_REFERENTIELS')],
        loadComponent: () => import('./features/etiquette/etiquette-list/etiquette-list').then(m => m.EtiquetteList),
      },
      {
        // Journal d'audit (DAT §7.4.3), lecture seule : CONSULTER_AUDIT.
        path: 'journal-audit',
        canActivate: [permissionGuard('CONSULTER_AUDIT')],
        loadComponent: () => import('./features/audit/journal-audit/journal-audit').then(m => m.JournalAudit),
      },
      {
        // Applications clientes et clés d'API (DAT §5.4) : GERER_CLES_API. Le
        // serveur refuse l'accès aux applications elles-mêmes.
        path: 'cles-api',
        canActivate: [permissionGuard('GERER_CLES_API'), moduleGuard('integration')],
        loadComponent: () => import('./features/cles-api/cles-api-admin/cles-api-admin').then(m => m.ClesApiAdmin),
      },
      {
        // Centre de notifications de l'utilisateur (DAT §12.9).
        path: 'notifications',
        canActivate: [moduleGuard('notifications')],
        loadComponent: () => import('./features/notifications/notifications-list/notifications-list').then(m => m.NotificationsList),
      },
      {
        path: 'televerser',
        loadComponent: () => import('./features/document/document-list/document-list').then(m => m.DocumentList),
      },
      {
        // Fiche d'un document : métadonnées, étiquettes, index, versions.
        path: 'televerser/:id',
        loadComponent: () => import('./features/document/document-detail/document-detail').then(m => m.DocumentDetail),
      },
      {
        // Recherche dans le contenu des documents (§4.4).
        path: 'recherche',
        canActivate: [moduleGuard('ocr')],
        loadComponent: () => import('./features/recherche/recherche-plein-texte/recherche-plein-texte').then(m => m.RecherchePleinTexte),
      },
      {
        // Exports de dossier préparés en arrière-plan (§12.10).
        path: 'mes-exports',
        canActivate: [moduleGuard('export')],
        loadComponent: () => import('./features/cycle-de-vie/mes-exports/mes-exports').then(m => m.MesExports),
      },
      {
        // Supervision des traitements OCR et réindexation (§4.3.4, §4.4.1).
        path: 'traitements-ocr',
        canActivate: [permissionGuard('SUPERVISER_TRAITEMENTS'), moduleGuard('ocr')],
        loadComponent: () => import('./features/recherche/supervision-ocr/supervision-ocr').then(m => m.SupervisionOcr),
      },
      {
        // Fiche de l'utilisateur : identité, droits, activité.
        path: 'profil',
        loadComponent: () => import('./features/profil/profil').then(m => m.ProfilPage),
      },
      {
        path: 'mes-workflow',
        canActivate: [moduleGuard('workflow')],
        loadComponent: () => import('./features/signature/mes-workflow/mes-workflow').then(m => m.MesWorkflow),
      },
      {
        // Administration des droits (lot E3, §12.2) : habilitations et
        // attribution d'un premier rôle, composition des rôles, droits effectifs.
        path: 'administration/habilitations',
        canActivate: [administrateurGuard],
        loadComponent: () => import('./features/administration/habilitations/habilitations').then(m => m.HabilitationsAdmin),
      },
      {
        path: 'administration/roles',
        canActivate: [administrateurGuard],
        loadComponent: () => import('./features/administration/roles/roles').then(m => m.RolesAdmin),
      },
      {
        path: 'administration/droits-effectifs',
        canActivate: [administrateurGuard],
        loadComponent: () => import('./features/administration/droits-effectifs/droits-effectifs').then(m => m.DroitsEffectifsAdmin),
      },
      {
        // Administration : révocation des sessions d'un utilisateur (risque R26).
        path: 'administration/sessions',
        canActivate: [administrateurGuard],
        loadComponent: () => import('./features/administration/sessions/sessions').then(m => m.SessionsAdmin),
      },
        ],
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
