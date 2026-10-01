import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { API_BASE } from '../../../core/api';
import { GED_ICONS } from '../../../core/ged-icons';
import { Critere } from '../../indexation/indexation.model';
import { DocumentItem } from '../document.model';
import { DocumentDetail } from './document-detail';

/**
 * ANO-F-025 : sur la fiche, l'échéance de conservation et la valeur d'un index
 * de type date s'affichent en jj/mm/aaaa, comme les autres dates — et non au
 * format technique aaaa-mm-jj renvoyé par l'API.
 */
describe('DocumentDetail — dates en jj/mm/aaaa (ANO-F-025)', () => {
  let serveur: HttpTestingController;

  const CHAMPS: Critere[] = [
    { id: 'i-ech', code: 'QA2_DATE_ECH', libelle: "Date d'échéance", fieldType: 'DATE', options: [], groupage: false, obligatoire: false },
    { id: 'i-ref', code: 'REF', libelle: 'Référence', fieldType: 'TEXTE', options: [], groupage: false, obligatoire: false },
  ];

  const DOC = {
    id: 'd1', name: 'Facture', workspace: { id: 'w', label: 'Achats' }, typeDocument: { id: 't', label: 'Facture' },
    fileName: 'facture.pdf', extension: 'pdf', sizeKo: 1, sizeLabel: '1 Ko', expirationDate: null, active: true,
    verrouille: false, chemin: 'Achats / Facture', createdBy: null, etiquettes: [], versions: [],
    createdAt: '2026-09-30T10:00:00Z', dateDocument: '2026-03-12', echeanceConservation: '2036-10-01',
    statutIndexation: 'INDEXE', permissions: ['CONSULTER'],
  } as DocumentItem;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [DocumentDetail],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNativeDateAdapter(),
        provideNoopAnimations(),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'd1' })) } },
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  });

  function ouvrir(): ComponentFixture<DocumentDetail> {
    const f = TestBed.createComponent(DocumentDetail);
    f.detectChanges();
    for (const r of serveur.match(() => true)) {
      const url = r.request.url;
      if (url === `${API_BASE}/documents/d1`) r.flush(DOC);
      else if (url === `${API_BASE}/indexation/documents/d1/champs`) r.flush(CHAMPS);
      else if (url === `${API_BASE}/indexation/documents/d1`) {
        r.flush([
          { indexFieldId: 'i-ech', code: 'QA2_DATE_ECH', libelle: "Date d'échéance", valeur: '2026-11-15' },
          { indexFieldId: 'i-ref', code: 'REF', libelle: 'Référence', valeur: '2026-11-15' },
        ]);
      } else if (url.endsWith('/etiquettes')) r.flush({ content: [], total: 0, page: 0, size: 200, totalPages: 0 });
      else if (url.endsWith('/for-select')) r.flush([]);
      else r.flush(null, { status: 404, statusText: 'Introuvable' });
    }
    f.detectChanges();
    return f;
  }

  function cartouche(f: ComponentFixture<DocumentDetail>, libelle: string): string | undefined {
    const c = [...(f.nativeElement as HTMLElement).querySelectorAll('.cartouche')]
      .find(e => e.querySelector('.lib')?.textContent?.trim() === libelle);
    return c?.querySelector('.val')?.textContent?.trim();
  }

  function index(f: ComponentFixture<DocumentDetail>, libelle: string): string | undefined {
    const lignes = [...(f.nativeElement as HTMLElement).querySelectorAll('.index-liste > div')];
    return lignes.find(l => l.querySelector('dt')?.textContent?.trim() === libelle)?.querySelector('dd')?.textContent?.trim();
  }

  it("affiche l'échéance de conservation en jj/mm/aaaa", () => {
    const f = ouvrir();
    expect(cartouche(f, 'Échéance de conservation')).toBe('01/10/2036');
    expect(cartouche(f, 'Date du document')).toBe('12/03/2026');
  });

  it("affiche un index de type date en jj/mm/aaaa ; un texte de même forme reste tel quel", () => {
    const f = ouvrir();
    expect(index(f, "Date d'échéance")).toBe('15/11/2026');
    expect(index(f, 'Référence')).toBe('2026-11-15');
  });
});
