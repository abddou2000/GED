import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { API_BASE } from '../../../core/api';
import { AuthService } from '../../../core/auth.service';
import { GED_ICONS } from '../../../core/ged-icons';
import { DocumentUpload } from '../../document/document-upload/document-upload';
import { DossierSimpleForm } from '../dossier-simple-form/dossier-simple-form';
import { WorkspaceForm } from '../workspace-form/workspace-form';
import { TreeNode, WorkSpace } from '../workspace.model';
import { WorkspaceDetail } from './workspace-detail';

/**
 * ANO-F-016 (écran) : dans un espace d'échange, « Nouveau dossier » ouvre le
 * formulaire simple (pas celui d'administration), et le dépôt lancé depuis un
 * dossier propose les dossiers de l'espace comme emplacement cible.
 */
describe('WorkspaceDetail — espace d\'échange', () => {
  let serveur: HttpTestingController;
  const ouvertures: { composant: unknown; config: { data?: unknown } }[] = [];

  const ARBRE: TreeNode[] = [{
    id: 'e1', name: 'Partage CPS', status: 'ACTIF', parentId: null, children: [
      { id: 'd1', name: 'Lot 1', status: 'ACTIF', parentId: 'e1', children: [
        { id: 'd2', name: 'Offres', status: 'ACTIF', parentId: 'd1', children: [] },
      ] },
    ],
  }, { id: 'm1', name: 'Achats', status: 'ACTIF', parentId: null, children: [] }];

  function espace(modif: Partial<WorkSpace> = {}): WorkSpace {
    return {
      id: 'd1', name: 'Lot 1', code: 'LOT1', description: null, status: 'ACTIF', owner: null,
      parent: { id: 'e1', label: 'Partage CPS' }, workflow: null, childrenCount: 1, usageEspace: 'ECHANGE',
      ...modif,
    };
  }

  beforeEach(() => {
    ouvertures.length = 0;
    TestBed.configureTestingModule({
      imports: [WorkspaceDetail],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'd1' })) } },
      ],
    });
    // MatDialogModule, importé par l'écran, fournit son propre MatDialog : on le remplace à la source.
    TestBed.overrideProvider(MatDialog, { useValue: {
      open: (composant: unknown, config: { data?: unknown }) => {
        ouvertures.push({ composant, config });
        return { afterClosed: () => of(false) };
      },
    } });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).utilisateur.set({
      id: 'u', identifiant: 'nidrissi', employeId: 'e', fullName: 'N. Idrissi', email: null, direction: null,
      roles: ['UTILISATEUR_STANDARD'], permissions: ['CONSULTER', 'DEPOSER'],
    });
  });

  function ouvrir(w: WorkSpace): ComponentFixture<WorkspaceDetail> {
    const f = TestBed.createComponent(WorkspaceDetail);
    f.detectChanges();
    serveur.expectOne(`${API_BASE}/workspaces/d1`).flush(w);
    serveur.expectOne(`${API_BASE}/workspaces/tree`).flush(ARBRE);
    serveur.expectOne(r => r.url === `${API_BASE}/documents`).flush({ content: [], total: 0, page: 0, size: 200, totalPages: 0 });
    f.detectChanges();
    // Le bloc du cycle de vie du dossier interroge le serveur : hors sujet ici.
    for (const r of serveur.match(() => true)) r.flush(null, { status: 404, statusText: 'Introuvable' });
    f.detectChanges();
    return f;
  }

  function bouton(f: ComponentFixture<WorkspaceDetail>, texte: string): HTMLButtonElement | undefined {
    return Array.from(f.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find(b => b.textContent?.includes(texte));
  }

  it('« Nouveau dossier » ouvre le formulaire simple, pas celui d\'administration', () => {
    const f = ouvrir(espace());
    bouton(f, 'Nouveau dossier')!.click();
    expect(ouvertures.length).toBe(1);
    expect(ouvertures[0].composant).toBe(DossierSimpleForm);
    expect(ouvertures[0].config.data).toEqual({ parentId: 'd1', parentNom: 'Lot 1' });
  });

  it("« Déposer ici » ouvre le dépôt avec les dossiers de l'espace d'échange, celui-ci proposé", () => {
    const f = ouvrir(espace());
    (f.nativeElement.querySelector('button.deposer-ici') as HTMLButtonElement).click();
    expect(ouvertures[0].composant).toBe(DocumentUpload);
    expect(ouvertures[0].config.data).toEqual({
      dossiers: [
        { id: 'e1', name: 'Partage CPS' },
        { id: 'd1', name: 'Partage CPS / Lot 1' },
        { id: 'd2', name: 'Partage CPS / Lot 1 / Offres' },
      ],
      dossierId: 'd1',
    });
  });

  it("espace métier : « Sous-dossier » garde le formulaire des espaces, et pas de « Déposer ici »", () => {
    // Hors espace d'échange, créer un sous-dossier relève de la gestion des espaces (ANO-F-018).
    TestBed.inject(AuthService).utilisateur.update(u => ({ ...u!, permissions: [...(u!.permissions ?? []), 'GERER_ESPACES'] }));
    const f = ouvrir(espace({ usageEspace: 'METIER' }));
    expect(f.nativeElement.querySelector('button.deposer-ici')).toBeNull();
    bouton(f, 'Sous-dossier')!.click();
    expect(ouvertures[0].composant).toBe(WorkspaceForm);
  });
});
