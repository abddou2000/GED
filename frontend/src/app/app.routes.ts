import { Routes } from '@angular/router';
import { WorkflowList } from './features/workflow/workflow-list/workflow-list';
import { WorkspaceList } from './features/workspace/workspace-list/workspace-list';
import { AccessGroupList } from './features/access-group/access-group-list/access-group-list';

export const routes: Routes = [
  { path: '', redirectTo: 'regles-de-workflow', pathMatch: 'full' },
  { path: 'regles-de-workflow', component: WorkflowList },
  { path: 'espaces-de-travail', component: WorkspaceList },
  { path: 'groupe-d-acces', component: AccessGroupList },
];
