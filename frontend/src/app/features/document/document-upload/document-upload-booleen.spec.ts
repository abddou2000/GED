import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MatDialogRef } from '@angular/material/dialog';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Subject } from 'rxjs';
import { API_BASE } from '../../../core/api';
import { GED_ICONS } from '../../../core/ged-icons';
import { DocumentUpload } from './document-upload';

/** ANO-F-020 : au dépôt, un index booléen se saisit par une case à cocher. */
describe('DocumentUpload — index booléen (ANO-F-020)', () => {
  let serveur: HttpTestingController;

  beforeEach(() => {
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
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  });

  function repondre(f: ComponentFixture<DocumentUpload>): void {
    for (let tour = 0; tour < 4; tour++) {
      for (const r of serveur.match(req => req.method === 'GET')) {
        const url = r.request.url;
        if (url === `${API_BASE}/type-documents/for-select`) r.flush([{ id: 't1', name: 'Facture', plan: 'Plan facture' }]);
        else if (url === `${API_BASE}/indices`) {
          r.flush({ content: [{ id: 'i-paye', code: 'PAYEE', nomIndex: 'Payée', fieldType: 'BOOLEEN', valeurs: null,
            valeurParDefaut: null, obligatoire: true, indexePourRecherche: false, indexDeGroupage: false }],
          total: 1, page: 0, size: 500, totalPages: 1 });
        } else if (url === `${API_BASE}/type-documents/t1`) r.flush({ id: 't1', planIndexation: { id: 'p1', label: 'Plan facture' } });
        else if (url === `${API_BASE}/plan-indexations/p1`) r.flush({ id: 'p1', indices: [{ id: 'i-paye', label: 'Payée' }], charteIds: [], separateur: '_' });
        else r.flush({ content: [], total: 0, page: 0, size: 0, totalPages: 0 });
      }
      f.detectChanges();
    }
  }

  it('propose une case à cocher, indéterminée tant que rien n\'est choisi, et envoie oui / non', async () => {
    const f = TestBed.createComponent(DocumentUpload);
    f.detectChanges();
    repondre(f);
    const c = f.componentInstance;
    c.form.patchValue({ typeDocumentId: 't1' });
    f.detectChanges();
    repondre(f);

    const el = f.nativeElement as HTMLElement;
    const caseACocher = el.querySelector('mat-checkbox[data-index="PAYEE"]');
    expect(caseACocher).not.toBeNull();
    expect(caseACocher!.textContent).toContain('Non renseigné');
    const input = caseACocher!.querySelector('input[type="checkbox"]') as HTMLInputElement;
    expect(input.indeterminate).toBe(true);

    input.click();
    f.detectChanges();
    expect(caseACocher!.textContent).toContain('Oui');
    input.click();
    f.detectChanges();
    expect(caseACocher!.textContent).toContain('Non');

    c.onFile({ target: { files: [new File(['x'], 'facture.pdf', { type: 'application/pdf' })] } } as unknown as Event);
    // L'aperçu d'indexation (facultatif) échoue : la saisie de l'opérateur est gardée.
    for (const r of serveur.match(req => req.url === `${API_BASE}/indexation/apercu`)) {
      r.flush(null, { status: 500, statusText: 'Erreur' });
    }
    c.submit();
    const depot = serveur.expectOne(r => r.method === 'POST' && r.url === `${API_BASE}/documents`);
    const metadonnees = (depot.request.body as FormData).get('metadonnees') as Blob;
    expect(JSON.parse(await metadonnees.text())).toEqual({ 'i-paye': 'non' });
  });
});
