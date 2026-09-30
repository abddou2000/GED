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

/** ANO-F-020 : la fiche affiche une métadonnée booléenne en Oui / Non, pas en « true ». */
describe('DocumentDetail — index booléen (ANO-F-020)', () => {
  let serveur: HttpTestingController;

  const CHAMPS: Critere[] = [
    { id: 'i-paye', code: 'PAYEE', libelle: 'Payée', fieldType: 'BOOLEEN', options: [], groupage: false, obligatoire: false },
    { id: 'i-four', code: 'FOURN', libelle: 'Fournisseur', fieldType: 'TEXTE', options: [], groupage: false, obligatoire: false },
  ];

  const DOC = {
    id: 'd1', name: 'Facture', workspace: { id: 'w', label: 'Achats' }, typeDocument: { id: 't', label: 'Facture' },
    fileName: 'facture.pdf', extension: 'pdf', sizeKo: 1, sizeLabel: '1 Ko', expirationDate: null, active: true,
    verrouille: false, chemin: 'Achats / Facture', createdBy: null, etiquettes: [], versions: [],
    createdAt: '2026-09-30T10:00:00Z', statutIndexation: 'INDEXE', permissions: ['CONSULTER', 'MODIFIER'],
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

  function ouvrir(paye: string): ComponentFixture<DocumentDetail> {
    const f = TestBed.createComponent(DocumentDetail);
    f.detectChanges();
    for (const r of serveur.match(() => true)) {
      const url = r.request.url;
      if (url === `${API_BASE}/documents/d1`) r.flush(DOC);
      else if (url === `${API_BASE}/indexation/documents/d1/champs`) r.flush(CHAMPS);
      else if (url === `${API_BASE}/indexation/documents/d1`) {
        r.flush([
          { indexFieldId: 'i-paye', code: 'PAYEE', libelle: 'Payée', valeur: paye },
          { indexFieldId: 'i-four', code: 'FOURN', libelle: 'Fournisseur', valeur: 'true' },
        ]);
      } else if (url.endsWith('/etiquettes')) r.flush({ content: [], total: 0, page: 0, size: 200, totalPages: 0 });
      else if (url.endsWith('/for-select')) r.flush([]);
      else r.flush(null, { status: 404, statusText: 'Introuvable' });
    }
    f.detectChanges();
    return f;
  }

  function valeurDe(f: ComponentFixture<DocumentDetail>, libelle: string): string | undefined {
    const lignes = [...(f.nativeElement as HTMLElement).querySelectorAll('.index-liste > div')];
    return lignes.find(l => l.querySelector('dt')?.textContent?.trim() === libelle)?.querySelector('dd')?.textContent?.trim();
  }

  it('affiche « Oui » pour true et « Non » pour false ; un texte reste tel quel', () => {
    let f = ouvrir('true');
    expect(valeurDe(f, 'Payée')).toBe('Oui');
    expect(valeurDe(f, 'Fournisseur')).toBe('true');
    f.destroy();
    f = ouvrir('false');
    expect(valeurDe(f, 'Payée')).toBe('Non');
  });

  it('la saisie des index reprend la valeur enregistrée en oui / non', () => {
    const f = ouvrir('true');
    const c = f.componentInstance;
    expect(c.valeurSaisie(CHAMPS[0])).toBe('oui');
    expect(c.valeurSaisie(CHAMPS[1])).toBe('true');
  });
});
