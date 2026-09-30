import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { API_BASE } from '../../../core/api';
import { ConfirmService } from '../../../core/confirm.service';
import { GED_ICONS } from '../../../core/ged-icons';
import { JobRetypage, TypeDocument } from '../type-document.model';
import { RetypageLot } from './retypage-lot';

/**
 * ANO-F-015 : écran de re-typologie en lot (`POST /type-documents/retypages`,
 * suivi du travail, rapport).
 */
describe('RetypageLot (ANO-F-015)', () => {
  let serveur: HttpTestingController;
  let query: Record<string, string> = {};

  const TYPES: TypeDocument[] = [
    { id: 'src', code: 'FACT', typeDeDocument: 'Facture', description: '', workspace: { id: 'w1', label: 'Achats' },
      planIndexation: { id: 'p1', label: 'Plan facture' }, typeAutorise: [], tailleMaxMo: 10, actif: true },
    { id: 'cib', code: 'FACF', typeDeDocument: 'Facture fournisseur', description: '', workspace: { id: 'w2', label: 'Compta' },
      planIndexation: { id: 'p2', label: 'Plan fournisseur' }, typeAutorise: [], tailleMaxMo: 10, actif: true },
    { id: 'off', code: 'OLD', typeDeDocument: 'Ancien', description: '', workspace: { id: 'w3', label: 'Archives' },
      planIndexation: null, typeAutorise: [], tailleMaxMo: 10, actif: false },
  ];
  const PLANS: Record<string, { id: string; label: string }[]> = {
    p1: [{ id: 'i-num', label: 'Numéro' }, { id: 'i-mont', label: 'Montant' }, { id: 'i-four', label: 'Fournisseur' }],
    p2: [{ id: 'i-num', label: 'Numéro' }, { id: 'i-ttc', label: 'Montant TTC' }],
  };
  const INDEX: Record<string, { code: string; nomIndex: string }> = {
    'i-num': { code: 'NUMERO', nomIndex: 'Numéro' },
    'i-mont': { code: 'MONTANT', nomIndex: 'Montant' },
    'i-four': { code: 'FOURNISSEUR', nomIndex: 'Fournisseur' },
    'i-ttc': { code: 'MONTANT_TTC', nomIndex: 'Montant TTC' },
  };

  function job(modif: Partial<JobRetypage> = {}): JobRetypage {
    return {
      id: 'j1', sourceTypeDocumentId: 'src', cibleTypeDocumentId: 'cib', correspondance: {}, statut: 'EN_ATTENTE',
      total: 2, traites: 0, reussis: 0, echecs: 0, rapport: [], creeLe: '2026-09-30T10:00:00Z', ...modif,
    };
  }

  beforeEach(() => {
    query = {};
    TestBed.configureTestingModule({
      imports: [RetypageLot],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: ActivatedRoute, useFactory: () => ({ snapshot: { queryParamMap: convertToParamMap(query) } }) },
        { provide: ConfirmService, useValue: { ask: () => of(true) } },
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  });

  /** Répond à tout ce qui est en attente (types, plans, index, documents, historique). */
  function repondre(historique: JobRetypage[] = []): void {
    for (let tour = 0; tour < 5; tour++) {
      for (const r of serveur.match(() => true)) {
        const url = r.request.url;
        if (url === `${API_BASE}/type-documents`) r.flush({ content: TYPES, total: 3, page: 0, size: 200, totalPages: 1 });
        else if (url === `${API_BASE}/type-documents/retypages`) r.flush(historique);
        else if (url.startsWith(`${API_BASE}/plan-indexations/`)) {
          const id = url.split('/').pop()!;
          r.flush({ id, indices: PLANS[id] });
        } else if (url.startsWith(`${API_BASE}/indices/`)) {
          const id = url.split('/').pop()!;
          r.flush({ id, ...INDEX[id] });
        } else if (url === `${API_BASE}/documents`) {
          r.flush({ content: [
            { id: 'd1', name: 'F-001', typeDocument: { id: 'src', label: 'Facture' } },
            { id: 'd2', name: 'Autre', typeDocument: { id: 'x', label: 'Autre' } },
            { id: 'd3', name: 'F-002', typeDocument: { id: 'src', label: 'Facture' } },
          ], total: 3, page: 0, size: 200, totalPages: 1 });
        } else r.flush(null, { status: 404, statusText: 'Introuvable' });
      }
    }
  }

  function ouvrir(historique: JobRetypage[] = []): ComponentFixture<RetypageLot> {
    const f = TestBed.createComponent(RetypageLot);
    f.detectChanges();
    repondre(historique);
    f.detectChanges();
    return f;
  }

  function choisir(f: ComponentFixture<RetypageLot>, sel: string, valeur: string): void {
    const s = (f.nativeElement as HTMLElement).querySelector(sel) as HTMLSelectElement;
    s.value = valeur;
    s.dispatchEvent(new Event('change'));
    f.detectChanges();
    repondre();
    f.detectChanges();
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;

  it('propose en cible les types actifs autres que la source', () => {
    const f = ouvrir();
    choisir(f, 'select[aria-label="Type source"]', 'src');
    const options = [...el(f).querySelectorAll('select[aria-label="Type cible"] option')].map(o => o.textContent?.trim());
    expect(options).toEqual(['Choisir…', 'Facture fournisseur']);
  });

  it('associe par défaut les champs de même code et signale les champs perdus', () => {
    const f = ouvrir();
    choisir(f, 'select[aria-label="Type source"]', 'src');
    choisir(f, 'select[aria-label="Type cible"]', 'cib');
    expect(f.componentInstance.correspondance()).toEqual({ NUMERO: 'NUMERO', MONTANT: '', FOURNISSEUR: '' });
    expect(el(f).textContent).toContain('Champs perdus : Montant, Fournisseur.');
  });

  it('lance le travail avec les seuls renommages, puis suit sa progression jusqu\'au rapport', () => {
    const f = ouvrir();
    choisir(f, 'select[aria-label="Type source"]', 'src');
    choisir(f, 'select[aria-label="Type cible"]', 'cib');
    choisir(f, 'select[data-champ="MONTANT"]', 'MONTANT_TTC');

    (el(f).querySelector('.act-lancer') as HTMLButtonElement).click();
    const post = serveur.expectOne(r => r.method === 'POST' && r.url === `${API_BASE}/type-documents/retypages`);
    expect(post.request.body).toEqual({
      sourceTypeDocumentId: 'src', cibleTypeDocumentId: 'cib',
      correspondance: { MONTANT: 'MONTANT_TTC' }, documentIds: [],
    });
    post.flush(job());
    f.detectChanges();
    expect(el(f).textContent).toContain('En attente');

    f.componentInstance.actualiserSuivi();
    serveur.expectOne(`${API_BASE}/type-documents/retypages/j1`).flush(job({
      statut: 'TERMINE', traites: 2, reussis: 1, echecs: 1, rapport: [
        { documentId: 'd1', nom: 'F-001', resultat: 'SUCCES', champsPerdus: ['FOURNISSEUR'] },
        { documentId: 'd3', nom: 'F-002', resultat: 'ECHEC', motif: 'DOCUMENT_VERROUILLE' },
      ],
    }));
    repondre();
    f.detectChanges();
    const texte = el(f).textContent ?? '';
    expect(texte).toContain('Terminé');
    expect(texte).toContain('2 / 2 traité(s) (100 %), 1 réussi(s), 1 échec(s)');
    expect(texte).toContain('Champs perdus : FOURNISSEUR');
    expect(texte).toContain('DOCUMENT_VERROUILLE');
  });

  it('n\'envoie que les documents choisis dans une sélection', () => {
    query = { source: 'src' };
    const f = ouvrir();
    expect(f.componentInstance.sourceId()).toBe('src');
    choisir(f, 'select[aria-label="Type cible"]', 'cib');
    const radio = el(f).querySelector('input[value="selection"]') as HTMLInputElement;
    radio.click();
    f.detectChanges();
    repondre();
    f.detectChanges();
    const cases = [...el(f).querySelectorAll('input[data-document]')] as HTMLInputElement[];
    expect(cases.map(c => c.dataset['document'])).toEqual(['d1', 'd3']);
    expect((el(f).querySelector('.act-lancer') as HTMLButtonElement).disabled).toBe(true);
    cases[1].click();
    f.detectChanges();
    (el(f).querySelector('.act-lancer') as HTMLButtonElement).click();
    const post = serveur.expectOne(r => r.method === 'POST');
    expect(post.request.body.documentIds).toEqual(['d3']);
  });

  it('affiche les derniers travaux', () => {
    const f = ouvrir([job({ statut: 'TERMINE', traites: 2, reussis: 2 })]);
    expect(el(f).querySelector('.historique')?.textContent).toContain('Facture → Facture fournisseur');
  });
});
