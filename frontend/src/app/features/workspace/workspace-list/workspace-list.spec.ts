import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../../core/auth.service';
import { GED_ICONS } from '../../../core/ged-icons';
import { WorkSpace } from '../workspace.model';
import { WorkspaceService } from '../workspace.service';
import { WorkspaceList } from './workspace-list';

/**
 * ANO-F-026 : modifier un espace ou un dossier relève de la gestion des
 * espaces (GERER_ESPACES) ; la liste et l'arborescence ne proposent
 * « Modifier » qu'à qui la détient (le serveur refuse les autres).
 */
describe('WorkspaceList — « Modifier » réservé à la gestion des espaces', () => {
  const ESPACE: WorkSpace = {
    id: 'w1', name: 'QA2 Projets', code: 'PRJ', description: null, status: 'ACTIF', owner: null, parent: null,
    workflow: null, childrenCount: 0, usageEspace: 'METIER',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [WorkspaceList],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap({})) } },
        { provide: WorkspaceService, useValue: {
          list: () => of({ content: [ESPACE], total: 1, page: 0, size: 50, totalPages: 1 }),
          trashed: () => of({ content: [], total: 0, page: 0, size: 50, totalPages: 0 }),
          tree: () => of([{ id: 'w1', name: 'QA2 Projets', status: 'ACTIF', parentId: null, children: [] }]),
          forSelect: () => of([{ id: 'w1', name: 'QA2 Projets' }]),
        } },
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
  });

  function identite(permissions: string[]): void {
    TestBed.inject(AuthService).utilisateur.set({
      id: 'u', identifiant: 'nidrissi', employeId: 'e', fullName: 'N. Idrissi', email: null, direction: null,
      roles: ['UTILISATEUR'], permissions,
    });
  }

  function ouvrir(vue: 'table' | 'tree'): ComponentFixture<WorkspaceList> {
    const f = TestBed.createComponent(WorkspaceList);
    f.detectChanges();
    f.componentInstance.setView(vue);
    f.detectChanges();
    return f;
  }

  /** « Modifier » du tableau, ou du menu d'actions de l'arborescence une fois ouvert. */
  function modifierPropose(f: ComponentFixture<WorkspaceList>, vue: 'table' | 'tree'): boolean {
    const racine = f.nativeElement as HTMLElement;
    if (vue === 'table') return racine.querySelector('.act-edit') !== null;
    (racine.querySelector('.tdots') as HTMLElement).click();
    f.detectChanges();
    return document.querySelector('.cdk-overlay-container .act-edit') !== null;
  }

  for (const vue of ['table', 'tree'] as const) {
    it(`vue ${vue} : un Utilisateur standard (Modifier sur le nœud) ne se voit pas proposer « Modifier »`, () => {
      identite(['CONSULTER', 'DEPOSER', 'MODIFIER']);
      expect(modifierPropose(ouvrir(vue), vue)).toBe(false);
    });

    it(`vue ${vue} : la gestion des espaces fait paraître « Modifier »`, () => {
      identite(['GERER_ESPACES']);
      expect(modifierPropose(ouvrir(vue), vue)).toBe(true);
    });
  }
});
