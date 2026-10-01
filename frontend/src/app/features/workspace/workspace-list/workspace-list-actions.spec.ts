import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectorRef } from '@angular/core';
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
 * Liste des espaces : chaque action ne paraît qu'à qui détient la permission
 * que le serveur exige (comme la fiche, ANO-F-018) — Archiver (ARCHIVER),
 * Supprimer et Restaurer (SUPPRIMER), Déplacer sous… (DEPLACER ; la racine :
 * GERER_ESPACES), Créer un sous-dossier (GERER_ESPACES).
 */
describe('WorkspaceList — actions selon les permissions', () => {
  const ESPACE: WorkSpace = {
    id: 'w1', name: 'QA2 Projets', code: 'PRJ', description: null, status: 'ACTIF', owner: null, parent: null,
    workflow: null, childrenCount: 0, usageEspace: 'METIER',
  };
  let corbeille = false;

  beforeEach(() => {
    corbeille = false;
    TestBed.configureTestingModule({
      imports: [WorkspaceList],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap({})) } },
        { provide: WorkspaceService, useValue: {
          list: () => of({ content: [ESPACE], total: 1, page: 0, size: 50, totalPages: 1 }),
          trashed: () => of({ content: corbeille ? [ESPACE] : [], total: 1, page: 0, size: 50, totalPages: 1 }),
          tree: () => of([{ id: 'w1', name: 'QA2 Projets', status: 'ACTIF', parentId: null, children: [] }]),
          forSelect: () => of([{ id: 'w1', name: 'QA2 Projets' }, { id: 'w2', name: 'QA2 Partage' }]),
        } },
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
  });

  afterEach(() => document.querySelector('.cdk-overlay-container')?.replaceChildren());

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

  /** Coche la ligne de la corbeille (la sélection n'est pas un signal : la vue est marquée à revoir). */
  function cocher(f: ComponentFixture<WorkspaceList>): void {
    f.componentInstance.selection.select(f.componentInstance.dataSource.data[0]);
    f.debugElement.injector.get(ChangeDetectorRef).markForCheck();
    f.detectChanges();
  }

  /** Libellés (info-bulles) des boutons d'action d'une ligne du tableau. */
  function actionsTableau(f: ComponentFixture<WorkspaceList>): string[] {
    return [...(f.nativeElement as HTMLElement).querySelectorAll('td.c-act button')]
      .map(b => b.getAttribute('aria-label') ?? '');
  }

  /** Entrées du menu d'actions d'un nœud de l'arborescence. */
  function actionsArbre(f: ComponentFixture<WorkspaceList>): string[] {
    ((f.nativeElement as HTMLElement).querySelector('.tdots') as HTMLElement).click();
    f.detectChanges();
    return [...document.querySelectorAll('.cdk-overlay-container .mat-mdc-menu-item')]
      .map(b => b.textContent?.trim() ?? '');
  }

  it('Utilisateur standard (Consulter, Déposer, Modifier) : seulement « Ouvrir le dossier »', () => {
    identite(['CONSULTER', 'DEPOSER', 'MODIFIER']);
    expect(actionsTableau(ouvrir('table'))).toEqual(['Ouvrir le dossier']);
    expect(actionsArbre(ouvrir('tree'))).toEqual(['Ouvrir le dossier']);
  });

  it('Archiver, Supprimer et Déplacer paraissent avec leur permission, sans la gestion des espaces', () => {
    identite(['CONSULTER', 'ARCHIVER', 'SUPPRIMER', 'DEPLACER']);
    expect(actionsTableau(ouvrir('table'))).toEqual(['Ouvrir le dossier', 'Archiver/Désarchiver', 'Supprimer']);
    const f = ouvrir('tree');
    expect(actionsArbre(f)).toEqual(['Ouvrir le dossier', 'Archiver / Désarchiver', 'Déplacer sous…', 'Supprimer']);
    // Sous-menu de déplacement : pas la racine (gestion des espaces), pas le dossier lui-même.
    (document.querySelector('.cdk-overlay-container .act-deplacer') as HTMLElement).click();
    f.detectChanges();
    const cibles = [...document.querySelectorAll('.cdk-overlay-container .mat-mdc-menu-panel')].at(-1)!;
    expect([...cibles.querySelectorAll('.mat-mdc-menu-item')].map(b => b.textContent?.trim())).toEqual(['QA2 Partage']);
  });

  it('la gestion des espaces fait paraître « Créer un sous-dossier » et « Modifier »', () => {
    identite(['GERER_ESPACES']);
    expect(actionsArbre(ouvrir('tree'))).toEqual(
      ['Ouvrir le dossier', 'Créer un sous-dossier', 'Modifier', 'Déplacer sous…']);
  });

  it('corbeille : « Restaurer » (un par un ou par lot) réservé à SUPPRIMER', () => {
    corbeille = true;
    identite(['CONSULTER']);
    let f = ouvrir('table');
    f.componentInstance.corbeilleView.set(true);
    f.componentInstance.load();
    f.detectChanges();
    expect(actionsTableau(f)).toEqual([]);
    cocher(f);
    expect(f.nativeElement.querySelector('button.bulk')).toBeNull();
    f.destroy();

    identite(['SUPPRIMER']);
    f = ouvrir('table');
    f.componentInstance.corbeilleView.set(true);
    f.componentInstance.load();
    f.detectChanges();
    expect(actionsTableau(f)).toEqual(['Restaurer']);
    cocher(f);
    expect(f.nativeElement.querySelector('button.bulk')).toBeTruthy();
  });
});
