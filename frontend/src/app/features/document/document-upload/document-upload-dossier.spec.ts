import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Subject } from 'rxjs';
import { API_BASE } from '../../../core/api';
import { GED_ICONS } from '../../../core/ged-icons';
import { DocumentUpload } from './document-upload';

/** ANO-F-016 (écran) : dépôt dans un dossier choisi d'un espace d'échange. */
describe('DocumentUpload — dossier cible', () => {
  let serveur: HttpTestingController;

  function configurer(data: unknown): void {
    const ref = {
      disableClose: false,
      backdropClick: () => new Subject<MouseEvent>(),
      keydownEvents: () => new Subject<KeyboardEvent>(),
      close: () => undefined,
    };
    TestBed.configureTestingModule({
      imports: [DocumentUpload],
      providers: [
        provideHttpClient(), provideHttpClientTesting(), provideNativeDateAdapter(), provideNoopAnimations(),
        { provide: MatDialogRef, useValue: ref },
        ...(data ? [{ provide: MAT_DIALOG_DATA, useValue: data }] : []),
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  }

  /** Répond aux lectures de l'ouverture : types, étiquettes, index. */
  function servirOuverture(): void {
    for (const r of serveur.match(() => true)) {
      if (r.request.url.endsWith('/for-select')) r.flush([{ id: 't1', name: 'Pièce CPS' }]);
      else r.flush({ content: [], total: 0, page: 0, size: 0, totalPages: 0 });
    }
  }

  function deposer(dossier?: string | null): FormData {
    const f = TestBed.createComponent(DocumentUpload);
    f.detectChanges();
    servirOuverture();
    f.detectChanges();
    const c = f.componentInstance;
    c.form.patchValue({ typeDocumentId: 't1' });
    if (dossier !== undefined) c.form.patchValue({ noeudId: dossier });
    c.onFile({ target: { files: [new File(['x'], 'offre.pdf', { type: 'application/pdf' })] } } as unknown as Event);
    c.submit();
    const depot = serveur.expectOne(r => r.method === 'POST' && r.url === `${API_BASE}/documents`);
    const corps = depot.request.body as FormData;
    depot.flush({ id: 'd9', statutIndexation: 'SANS_PLAN' });
    return corps;
  }

  it("propose les dossiers de l'espace d'échange et envoie le dossier choisi (noeudId)", () => {
    configurer({ dossiers: [{ id: 'e1', name: 'Partage' }, { id: 'd1', name: 'Partage / Lot 1' }], dossierId: 'd1' });
    const f = TestBed.createComponent(DocumentUpload);
    f.detectChanges();
    expect(f.nativeElement.querySelector('mat-select.dossier-cible')).toBeTruthy();
    expect(f.componentInstance.form.value.noeudId).toBe('d1');   // le dossier d'où l'on vient
    servirOuverture();
    f.destroy();

    expect(deposer().get('noeudId')).toBe('d1');
  });

  it('« Dossier du type » : aucun emplacement envoyé', () => {
    configurer({ dossiers: [{ id: 'e1', name: 'Partage' }], dossierId: 'e1' });
    expect(deposer(null).get('noeudId')).toBeNull();
  });

  it('hors espace d\'échange : ni champ Dossier ni noeudId', () => {
    configurer(null);
    const f = TestBed.createComponent(DocumentUpload);
    f.detectChanges();
    expect(f.nativeElement.querySelector('mat-select.dossier-cible')).toBeNull();
    servirOuverture();
    f.destroy();
    expect(deposer().get('noeudId')).toBeNull();
  });
});
