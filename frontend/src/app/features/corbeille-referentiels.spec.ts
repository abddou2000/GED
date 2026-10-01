import { Type } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { GED_ICONS } from '../core/ged-icons';
import { AccessGroupList } from './access-group/access-group-list/access-group-list';
import { EtiquetteList } from './etiquette/etiquette-list/etiquette-list';
import { IndexList } from './index/index-list/index-list';
import { PlanIndexationList } from './plan-indexation/plan-indexation-list/plan-indexation-list';
import { TypeDocumentList } from './type-document/type-document-list/type-document-list';
import { WorkflowList } from './workflow/workflow-list/workflow-list';
import { WorkspaceList } from './workspace/workspace-list/workspace-list';

/**
 * Corbeille des référentiels et des espaces (suite d'ANO-F-023, tour 3).
 *
 * La vue des éléments supprimés (`?trashed=1` : restauration, suppression
 * réversible, §3.6 et §4.6.5) s'appelait « Archive », comme l'archivage, qui est
 * une autre fonction (statut « archivé », lecture seule, D10). Chaque liste doit
 * la nommer « Corbeille », dans le bouton comme dans l'état vide.
 */
const LISTES: [string, Type<unknown>, string][] = [
  ['index', IndexList, 'Index'],
  ["plans d'indexation", PlanIndexationList, "Plans d'indexation"],
  ['types de document', TypeDocumentList, 'Types de document'],
  ['étiquettes', EtiquetteList, 'Étiquettes'],
  ['règles de workflow', WorkflowList, 'Règles de workflow'],
  ["groupes d'accès", AccessGroupList, 'Groupes'],
  ['espaces de travail', WorkspaceList, 'Espaces de travail'],
];

const PAGE_VIDE = { content: [], total: 0, page: 0, size: 10, totalPages: 0 };

describe('Corbeille des listes de référentiels et des espaces', () => {
  let serveur: HttpTestingController;

  function ouvrir(composant: Type<unknown>, corbeille: boolean): HTMLElement {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [composant],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap(corbeille ? { trashed: '1' } : {})) } },
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
    const f: ComponentFixture<unknown> = TestBed.createComponent(composant);
    f.detectChanges();
    // Liste paginée vide pour la liste elle-même, tableau vide pour le reste
    // (état des modules, arborescence, listes de choix).
    for (const req of serveur.match(() => true)) {
      req.flush(req.request.url.endsWith('/trashed') || req.request.params.has('page') ? PAGE_VIDE : []);
    }
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  }

  function libelles(el: HTMLElement): string[] {
    return Array.from(el.querySelectorAll('button')).map(b => (b.textContent ?? '').replace(/\s+/g, ' ').trim());
  }

  for (const [nom, composant, retour] of LISTES) {
    it(`${nom} : le bouton s'appelle « Corbeille », jamais « Archive »`, () => {
      const el = ouvrir(composant, false);
      expect((el.querySelector('button.corbeille')?.textContent ?? '').trim()).toBe('Corbeille');
      expect(libelles(el)).not.toContain('Archive');
    });

    it(`${nom} : dans la corbeille, état vide « La corbeille est vide » et retour à la liste`, () => {
      const el = ouvrir(composant, true);
      expect(serveur.match(() => true)).toEqual([]);
      expect((el.querySelector('button.corbeille')?.textContent ?? '').trim()).toBe(retour);
      expect(el.textContent).toContain('La corbeille est vide');
      expect(el.textContent).not.toMatch(/archive est vide/i);
      expect(libelles(el)).not.toContain('Actifs');
    });
  }
});
