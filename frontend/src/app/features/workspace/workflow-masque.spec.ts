import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { API_BASE } from '../../core/api';
import { GED_ICONS } from '../../core/ged-icons';
import { CodeModule, ModulesService } from '../../core/modules.service';
import { WorkSpace } from './workspace.model';
import { WorkspaceForm } from './workspace-form/workspace-form';
import { WorkspaceList } from './workspace-list/workspace-list';

/**
 * Règle de workflow des dossiers et module workflow (T-088) : quand le module
 * est désactivé, le formulaire d'espace ne propose plus la règle (et ne lit
 * plus `/workflow/regles`, qui répondrait 404), la liste n'a plus de colonne
 * « Circuit ». Actif, rien ne change.
 */
describe('Espaces de travail et module workflow (T-088)', () => {
  let serveur: HttpTestingController;
  const inactifs = new Set<CodeModule>();

  const ESPACE: WorkSpace = {
    id: 'w1', name: 'Achats', code: 'ACH', description: null, status: 'ACTIF', owner: null, parent: null,
    workflow: { id: 'r1', label: 'Visa DAF' }, childrenCount: 0, usageEspace: 'METIER',
  };

  function configurer(extra: unknown[] = []): void {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: ModulesService, useValue: { charger: () => of(undefined), actif: (c: CodeModule) => !inactifs.has(c) } },
        ...extra as never[],
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  }

  beforeEach(() => inactifs.clear());

  describe('formulaire d\'espace', () => {
    function ouvrir(): HTMLElement {
      configurer([
        { provide: MAT_DIALOG_DATA, useValue: { workspace: ESPACE } },
        { provide: MatDialogRef, useValue: { close: () => undefined } },
      ]);
      const f = TestBed.createComponent(WorkspaceForm);
      f.detectChanges();
      for (const r of serveur.match(r => r.url === `${API_BASE}/workflow/regles`)) {
        r.flush({ content: [{ id: 'r1', name: 'Visa DAF' }], total: 1, page: 0, size: 1000, totalPages: 1 });
      }
      f.detectChanges();
      return f.nativeElement as HTMLElement;
    }

    it('module actif : la règle de workflow est proposée', () => {
      const el = ouvrir();
      expect(el.querySelector('.champ-workflow')?.textContent).toContain('Règle de workflow');
    });

    it('module inactif : ni champ, ni lecture des règles', () => {
      inactifs.add('workflow');
      const el = ouvrir();
      serveur.expectNone(r => r.url.startsWith(`${API_BASE}/workflow`));
      expect(el.querySelector('.champ-workflow')).toBeNull();
      expect(el.textContent).not.toContain('Règle de workflow');
    });

    it('module inactif : l\'enregistrement renvoie la règle déjà portée par le dossier', () => {
      inactifs.add('workflow');
      configurer([
        { provide: MAT_DIALOG_DATA, useValue: { workspace: ESPACE } },
        { provide: MatDialogRef, useValue: { close: () => undefined } },
      ]);
      const f = TestBed.createComponent(WorkspaceForm);
      f.detectChanges();
      f.componentInstance.form.patchValue({ employeId: 'e1' });
      f.componentInstance.submit();
      const req = serveur.expectOne(r => r.url === `${API_BASE}/workspaces/w1` && r.method === 'PUT');
      expect(req.request.body.workflowId).toBe('r1');
    });
  });

  describe('liste des espaces', () => {
    function ouvrir(): HTMLElement {
      configurer([
        { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap({})) } },
      ]);
      const f = TestBed.createComponent(WorkspaceList);
      f.detectChanges();
      f.componentInstance.setView('table');
      f.detectChanges();
      for (let tour = 0; tour < 2; tour++) {
        for (const r of serveur.match(() => true)) {
          r.flush(r.request.params.has('page')
            ? { content: [ESPACE], total: 1, page: 0, size: 10, totalPages: 1 } : []);
        }
        f.detectChanges();
      }
      return f.nativeElement as HTMLElement;
    }

    const entetes = (el: HTMLElement) =>
      Array.from(el.querySelectorAll('th')).map(th => (th.textContent ?? '').trim());

    it('module actif : colonne « Circuit »', () => {
      const el = ouvrir();
      expect(entetes(el)).toContain('Circuit');
      expect(el.textContent).toContain('Visa DAF');
    });

    it('module inactif : pas de colonne « Circuit »', () => {
      inactifs.add('workflow');
      const el = ouvrir();
      expect(entetes(el)).not.toContain('Circuit');
      expect(el.textContent).not.toContain('Visa DAF');
    });
  });
});
