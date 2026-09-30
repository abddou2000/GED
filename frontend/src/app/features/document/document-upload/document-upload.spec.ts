import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MatDialogRef } from '@angular/material/dialog';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Subject } from 'rxjs';
import { API_BASE } from '../../../core/api';
import { GED_ICONS } from '../../../core/ged-icons';
import { DocumentUpload } from './document-upload';

/** ANO-F-006 : le formulaire de dépôt porte l'objet et la date du document. */
describe('DocumentUpload', () => {
  let serveur: HttpTestingController;
  const fermetures: unknown[] = [];

  beforeEach(() => {
    fermetures.length = 0;
    const ref = {
      disableClose: false,
      backdropClick: () => new Subject<MouseEvent>(),
      keydownEvents: () => new Subject<KeyboardEvent>(),
      close: (v: unknown) => fermetures.push(v),
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

  it("envoie l'objet et la date du document saisis avec le dépôt", () => {
    const f = TestBed.createComponent(DocumentUpload);
    f.detectChanges();
    for (const r of serveur.match(() => true)) {
      if (r.request.url.endsWith('/for-select')) r.flush([{ id: 't1', name: 'Courrier' }]);   // type sans plan
      else r.flush({ content: [], total: 0, page: 0, size: 0, totalPages: 0 });
    }
    f.detectChanges();
    const el: HTMLElement = f.nativeElement;
    expect(el.querySelector('textarea[formcontrolname="objet"]')).toBeTruthy();
    expect(el.querySelector('input[formcontrolname="dateDocument"]')).toBeTruthy();

    const c = f.componentInstance;
    c.form.patchValue({ typeDocumentId: 't1', objet: 'Relance fournisseur', dateDocument: new Date(2026, 2, 12) });
    c.onFile({ target: { files: [new File(['x'], 'courrier.pdf', { type: 'application/pdf' })] } } as unknown as Event);
    c.submit();

    const depot = serveur.expectOne(r => r.method === 'POST' && r.url === `${API_BASE}/documents`);
    const corps = depot.request.body as FormData;
    expect(corps.get('objet')).toBe('Relance fournisseur');
    expect(corps.get('dateDocument')).toBe('2026-03-12');
    depot.flush({ id: 'd1', statutIndexation: 'SANS_PLAN' });
    expect(fermetures).toEqual(['sans-plan']);
  });
});
