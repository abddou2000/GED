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
import { CodeModule, ModulesService } from '../../../core/modules.service';
import { DocumentItem } from '../document.model';
import { DocumentDetail } from './document-detail';

/**
 * T-088 : la fiche d'un document masque les actions du cycle de vie (archiver,
 * désarchiver) et le circuit de validation quand leur module est désactivé.
 */
describe('DocumentDetail — modules métier (T-088)', () => {
  let serveur: HttpTestingController;
  const inactifs = new Set<CodeModule>();
  /** Adresses demandées au serveur pendant le test. */
  const demandees: string[] = [];

  function doc(modif: Partial<DocumentItem> = {}): DocumentItem {
    return {
      id: 'd1', name: 'Facture', workspace: { id: 'w', label: 'Achats' }, typeDocument: { id: 't', label: 'Facture' },
      fileName: 'facture.pdf', extension: 'pdf', sizeKo: 1, sizeLabel: '1 Ko', expirationDate: null, active: true,
      verrouille: false, chemin: 'Achats / Facture', createdBy: null, etiquettes: [], versions: [],
      createdAt: '2026-09-30T10:00:00Z', statutIndexation: 'INDEXE', permissions: ['CONSULTER', 'MODIFIER', 'ARCHIVER'],
      ...modif,
    };
  }

  beforeEach(() => {
    inactifs.clear();
    demandees.length = 0;
    TestBed.configureTestingModule({
      imports: [DocumentDetail],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNativeDateAdapter(),
        provideNoopAnimations(),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'd1' })) } },
        { provide: ModulesService, useValue: { charger: () => of(undefined), actif: (c: CodeModule) => !inactifs.has(c) } },
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  });

  function ouvrir(d: DocumentItem): ComponentFixture<DocumentDetail> {
    const f = TestBed.createComponent(DocumentDetail);
    f.detectChanges();
    for (let tour = 0; tour < 2; tour++) {
      for (const r of serveur.match(() => true)) {
        const url = r.request.url;
        demandees.push(url);
        if (url === `${API_BASE}/documents/d1`) r.flush(d);
        else if (url.startsWith(`${API_BASE}/indexation/`)) r.flush([]);
        else if (url.endsWith('/etiquettes')) r.flush({ content: [], total: 0, page: 0, size: 200, totalPages: 0 });
        else if (url.endsWith('/for-select')) r.flush([]);
        else r.flush(null, { status: 404, statusText: 'Introuvable' });
      }
      f.detectChanges();
    }
    return f;
  }

  const boutons = (f: ComponentFixture<unknown>) =>
    [...(f.nativeElement as HTMLElement).querySelectorAll('.tete-actions button')].map(b => b.textContent?.trim() ?? '');
  const circuit = (f: ComponentFixture<unknown>) =>
    (f.nativeElement as HTMLElement).querySelector('app-circuit-document');

  it('modules actifs : Archiver et le circuit de validation sont proposés', () => {
    const f = ouvrir(doc());
    expect(boutons(f).some(b => b.includes('Archiver'))).toBe(true);
    expect(circuit(f)).not.toBeNull();
  });

  it('cycle de vie inactif : ni Archiver, ni Désarchiver, ni lecture de la conservation', () => {
    inactifs.add('cycledevie');
    let f = ouvrir(doc());
    expect(boutons(f).some(b => b.includes('Archiver'))).toBe(false);
    f.destroy();
    f = ouvrir(doc({ statutConservation: 'ARCHIVE', archiveLe: '2026-09-01T00:00:00Z' }));
    expect(boutons(f).some(b => b.includes('Désarchiver'))).toBe(false);
    expect(demandees.some(u => u.endsWith('/conservation'))).toBe(false);
  });

  it('workflow inactif : pas de circuit de validation', () => {
    inactifs.add('workflow');
    expect(circuit(ouvrir(doc()))).toBeNull();
  });
});
