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
import { ConfirmService } from '../../../core/confirm.service';
import { GED_ICONS } from '../../../core/ged-icons';
import { CodeModule, ModulesService } from '../../../core/modules.service';
import { TypeDocument } from '../type-document.model';
import { TypeDocumentDetail } from './type-document-detail';

/**
 * ANO-F-015 : la fiche d'un type affiche son statut et permet de le désactiver
 * ou de le réactiver (`PATCH /type-documents/{id}/actif`) ; elle mène à la
 * re-typologie en lot quand le module « cycle de vie » est actif.
 */
describe('TypeDocumentDetail (ANO-F-015)', () => {
  let serveur: HttpTestingController;
  const inactifs = new Set<CodeModule>();
  let confirmer = true;

  function type(modif: Partial<TypeDocument> = {}): TypeDocument {
    return {
      id: 't1', code: 'FACT', typeDeDocument: 'Facture', description: '', workspace: { id: 'w', label: 'Achats' },
      planIndexation: null, typeAutorise: ['pdf'], tailleMaxMo: 10, actif: true, ...modif,
    };
  }

  beforeEach(() => {
    inactifs.clear();
    confirmer = true;
    TestBed.configureTestingModule({
      imports: [TypeDocumentDetail],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 't1' })) } },
        { provide: ModulesService, useValue: { charger: () => of(undefined), actif: (c: CodeModule) => !inactifs.has(c) } },
        { provide: ConfirmService, useValue: { ask: () => of(confirmer) } },
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    TestBed.inject(AuthService).utilisateur.set({
      id: 'u', identifiant: 'admin', employeId: 'e', fullName: 'Admin', email: null, direction: null,
      roles: ['ADMINISTRATEUR'], permissions: ['GERER_REFERENTIELS'],
    });
    serveur = TestBed.inject(HttpTestingController);
  });

  function ouvrir(t: TypeDocument): ComponentFixture<TypeDocumentDetail> {
    const f = TestBed.createComponent(TypeDocumentDetail);
    f.detectChanges();
    for (const r of serveur.match(() => true)) {
      if (r.request.url === `${API_BASE}/type-documents/t1`) r.flush(t);
      else r.flush({ content: [], total: 0, page: 0, size: 200, totalPages: 0 });
    }
    f.detectChanges();
    return f;
  }

  const texte = (f: ComponentFixture<unknown>) => (f.nativeElement as HTMLElement).textContent ?? '';
  const bouton = (f: ComponentFixture<unknown>, sel: string) =>
    (f.nativeElement as HTMLElement).querySelector(sel) as HTMLElement | null;

  it('affiche le statut du type', () => {
    expect(texte(ouvrir(type()))).toContain('Actif');
  });

  it('désactive un type actif après confirmation', () => {
    const f = ouvrir(type());
    const b = bouton(f, '.act-statut')!;
    expect(b.textContent).toContain('Désactiver');
    b.click();
    const req = serveur.expectOne(r => r.url === `${API_BASE}/type-documents/t1/actif`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.params.get('actif')).toBe('false');
    req.flush(type({ actif: false }));
    f.detectChanges();
    expect(texte(f)).toContain('Désactivé');
    expect(bouton(f, '.act-statut')!.textContent).toContain('Réactiver');
  });

  it('n\'envoie rien si la désactivation est annulée', () => {
    confirmer = false;
    const f = ouvrir(type());
    bouton(f, '.act-statut')!.click();
    serveur.expectNone(r => r.url.endsWith('/actif'));
  });

  it('réactive un type désactivé', () => {
    const f = ouvrir(type({ actif: false }));
    bouton(f, '.act-statut')!.click();
    const req = serveur.expectOne(r => r.url === `${API_BASE}/type-documents/t1/actif`);
    expect(req.request.params.get('actif')).toBe('true');
    req.flush(type({ actif: true }));
    f.detectChanges();
    expect(bouton(f, '.act-statut')!.textContent).toContain('Désactiver');
  });

  it('mène à la re-typologie seulement si le module cycle de vie est actif', () => {
    expect(bouton(ouvrir(type()), '.act-retyper')).not.toBeNull();
    inactifs.add('cycledevie');
    expect(bouton(ouvrir(type()), '.act-retyper')).toBeNull();
  });
});
