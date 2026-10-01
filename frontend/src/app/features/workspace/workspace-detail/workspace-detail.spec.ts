import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { API_BASE } from '../../../core/api';
import { AuthService } from '../../../core/auth.service';
import { GED_ICONS } from '../../../core/ged-icons';
import { CodeModule, ModulesService } from '../../../core/modules.service';
import { WorkSpace } from '../workspace.model';
import { WorkspaceDetail } from './workspace-detail';

/**
 * Fiche d'un dossier : n'affiche que les actions permises par les droits
 * effectifs de l'appelant sur le nœud (ANO-F-018) et par les modules actifs
 * (T-088).
 */
describe('WorkspaceDetail', () => {
  let serveur: HttpTestingController;
  const inactifs = new Set<CodeModule>();

  function espace(modif: Partial<WorkSpace> = {}): WorkSpace {
    return {
      id: 'w1', name: 'QA2 Partage', code: 'PART', description: null, status: 'ACTIF', owner: null, parent: null,
      workflow: null, childrenCount: 0, usageEspace: 'METIER', permissions: ['CONSULTER'], ...modif,
    };
  }

  beforeEach(() => {
    inactifs.clear();
    TestBed.configureTestingModule({
      imports: [WorkspaceDetail],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'w1' })) } },
        { provide: ModulesService, useValue: { charger: () => of(undefined), actif: (c: CodeModule) => !inactifs.has(c) } },
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  });

  function identite(permissions: string[]): void {
    TestBed.inject(AuthService).utilisateur.set({
      id: 'u', identifiant: 'nidrissi', employeId: 'e', fullName: 'N. Idrissi', email: null, direction: null,
      roles: ['UTILISATEUR'], permissions,
    });
  }

  function ouvrir(w: WorkSpace): ComponentFixture<WorkspaceDetail> {
    const f = TestBed.createComponent(WorkspaceDetail);
    f.detectChanges();
    for (let tour = 0; tour < 3; tour++) {
      for (const r of serveur.match(() => true)) {
        const url = r.request.url;
        if (url === `${API_BASE}/workspaces/w1`) r.flush(w);
        else if (url === `${API_BASE}/workspaces/tree`) r.flush([{ id: 'w1', name: w.name, status: 'ACTIF', parentId: null, children: [] }]);
        else if (url === `${API_BASE}/documents`) {
          r.flush({ content: [{ id: 'd1', name: 'Note', typeDocument: null, sizeLabel: '1 Ko', fileName: 'note.pdf' }],
            total: 1, page: 0, size: 200, totalPages: 1 });
        } else if (url.includes('/archivage/dossiers/')) r.flush({ statutConservation: 'ACTIF' });
        else if (url.endsWith('/archivage/jobs')) r.flush([]);
        else r.flush(null, { status: 404, statusText: 'Introuvable' });
      }
      f.detectChanges();
    }
    return f;
  }

  const present = (f: ComponentFixture<unknown>, sel: string) =>
    (f.nativeElement as HTMLElement).querySelector(sel) !== null;

  it('T-088 : le circuit de validation du dossier suit le module workflow', () => {
    identite(['CONSULTER']);
    const w = espace({ workflow: { id: 'r1', label: 'Visa DAF' } });
    expect((ouvrir(w).nativeElement as HTMLElement).querySelector('.circuit')?.textContent).toContain('Visa DAF');
    inactifs.add('workflow');
    const f = ouvrir(w);
    expect(present(f, '.circuit')).toBe(false);
    expect((f.nativeElement as HTMLElement).textContent).not.toContain('Circuit de validation');
  });

  describe('actions selon les droits du nœud (ANO-F-018)', () => {
    it('un simple lecteur ne voit ni Modifier, ni Sous-dossier, ni Archiver le dossier, ni Supprimer', () => {
      identite(['CONSULTER']);
      const f = ouvrir(espace({ permissions: ['CONSULTER'] }));
      expect(present(f, '.act-modifier')).toBe(false);
      expect(present(f, '.act-sous-dossier')).toBe(false);
      expect(present(f, '.act-archiver')).toBe(false);
      expect(present(f, '.act-supprimer')).toBe(false);
      // Télécharger reste proposé : consulter suffit.
      expect(present(f, 'button[aria-label="Télécharger"]')).toBe(true);
    });

    it('chaque permission du nœud fait paraître son action', () => {
      identite(['CONSULTER']);
      const f = ouvrir(espace({ permissions: ['CONSULTER', 'MODIFIER', 'ARCHIVER', 'SUPPRIMER'] }));
      expect(present(f, '.act-modifier')).toBe(true);
      expect(present(f, '.act-archiver')).toBe(true);
      expect(present(f, '.act-supprimer')).toBe(true);
      expect(present(f, '.act-sous-dossier')).toBe(false);
    });

    it('Sous-dossier : Déposer dans un espace d\'échange, ou la gestion des espaces', () => {
      identite(['CONSULTER', 'DEPOSER']);
      expect(present(ouvrir(espace({ usageEspace: 'ECHANGE', permissions: ['CONSULTER', 'DEPOSER'] })), '.act-sous-dossier'))
        .toBe(true);
      expect(present(ouvrir(espace({ usageEspace: 'METIER', permissions: ['CONSULTER', 'DEPOSER'] })), '.act-sous-dossier'))
        .toBe(false);
      identite(['GERER_ESPACES']);
      expect(present(ouvrir(espace({ permissions: ['CONSULTER'] })), '.act-sous-dossier')).toBe(true);
    });
  });
});
