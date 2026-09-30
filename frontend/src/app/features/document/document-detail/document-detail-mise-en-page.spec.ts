import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { API_BASE } from '../../../core/api';
import { GED_ICONS } from '../../../core/ged-icons';
import { DocumentDetail } from './document-detail';

/**
 * ANO-F-021 : le bloc Informations / Étiquettes / Champs indexés garde sa
 * hauteur naturelle ; il n'est plus comprimé dans la hauteur restante de la
 * fenêtre avec défilement interne (16 à 84 px visibles sur un portable). La
 * fiche défile dans son cadre. Mesure réelle en 1366×768 : voir
 * docs/conformite/suivi/dev5.md.
 */
describe('DocumentDetail — mise en page (ANO-F-021)', () => {
  it('les colonnes ne rétrécissent pas et ne défilent pas en interne', () => {
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
    const serveur = TestBed.inject(HttpTestingController);

    const f = TestBed.createComponent(DocumentDetail);
    f.detectChanges();
    for (const r of serveur.match(() => true)) {
      if (r.request.url === `${API_BASE}/documents/d1`) {
        r.flush({
          id: 'd1', name: 'Facture', workspace: null, typeDocument: null, fileName: 'f.pdf', extension: 'pdf',
          sizeKo: 1, sizeLabel: '1 Ko', expirationDate: null, active: true, verrouille: false, chemin: '',
          createdBy: null, etiquettes: [], versions: [], createdAt: '2026-09-30T10:00:00Z',
          statutIndexation: 'A_INDEXER', permissions: ['CONSULTER', 'MODIFIER'],
        });
      } else if (r.request.url.endsWith('/etiquettes')) {
        r.flush({ content: [], total: 0, page: 0, size: 200, totalPages: 0 });
      } else {
        r.flush([]);
      }
    }
    f.detectChanges();

    const colonnes = f.nativeElement.querySelector('.colonnes') as HTMLElement;
    expect(colonnes).toBeTruthy();
    expect(getComputedStyle(colonnes).flexShrink).toBe('0');
    for (const colonne of Array.from(colonnes.children) as HTMLElement[]) {
      const style = getComputedStyle(colonne);
      expect(style.overflow === 'auto' || style.overflowY === 'auto').toBe(false);
      expect(style.maxHeight).not.toBe('100%');
    }
  });
});
