import { Routes } from '@angular/router';

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
    loadComponent: () => import('./layout/shell/shell').then(m => m.Shell),
    children: [
      { path: '', redirectTo: 'accueil', pathMatch: 'full' },
      {
        path: 'accueil',
        loadComponent: () => import('./features/home/dashboard/dashboard').then(m => m.Dashboard),
      },
      {
        path: 'recherche',
        loadComponent: () => import('./features/indexation/recherche-indexee/recherche-indexee').then(m => m.RechercheIndexee),
      },
      {
        path: 'regles-de-workflow',
        loadComponent: () => import('./features/workflow/workflow-list/workflow-list').then(m => m.WorkflowList),
      },
      {
        path: 'espaces-de-travail',
        loadComponent: () => import('./features/workspace/workspace-list/workspace-list').then(m => m.WorkspaceList),
      },
      {
        path: 'groupe-d-acces',
        loadComponent: () => import('./features/access-group/access-group-list/access-group-list').then(m => m.AccessGroupList),
      },
      {
        path: 'index',
        loadComponent: () => import('./features/index/index-list/index-list').then(m => m.IndexList),
      },
      {
        path: 'plan-indexation',
        loadComponent: () => import('./features/plan-indexation/plan-indexation-list/plan-indexation-list').then(m => m.PlanIndexationList),
      },
      {
        path: 'type-de-document',
        loadComponent: () => import('./features/type-document/type-document-list/type-document-list').then(m => m.TypeDocumentList),
      },
      {
        path: 'etiquette',
        loadComponent: () => import('./features/etiquette/etiquette-list/etiquette-list').then(m => m.EtiquetteList),
      },
      {
        path: 'televerser',
        loadComponent: () => import('./features/document/document-list/document-list').then(m => m.DocumentList),
      },
      {
        path: 'mes-workflow',
        loadComponent: () => import('./features/signature/mes-workflow/mes-workflow').then(m => m.MesWorkflow),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
