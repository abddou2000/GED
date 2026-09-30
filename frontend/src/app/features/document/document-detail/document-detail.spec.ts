import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
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
 * Fiche du document : reprise d'un document « à indexer » (ANO-F-005) et
 * correction de l'objet et de la date du document (ANO-F-006).
 */
describe('DocumentDetail', () => {
  let serveur: HttpTestingController;

  const CHAMPS: Critere[] = [
    { id: 'i-fourn', code: 'T_FOURN', libelle: 'Fournisseur', fieldType: 'TEXTE', options: [], groupage: false, obligatoire: true },
    { id: 'i-mont', code: 'T_MONT', libelle: 'Montant', fieldType: 'NOMBRE', options: [], groupage: false, obligatoire: false },
  ];

  function doc(modif: Partial<DocumentItem> = {}): DocumentItem {
    return {
      id: 'd1', name: 'Facture', workspace: { id: 'w', label: 'Achats' }, typeDocument: { id: 't', label: 'Facture' },
      fileName: 'facture.pdf', extension: 'pdf', sizeKo: 1, sizeLabel: '1 Ko', expirationDate: null, active: true,
      verrouille: false, chemin: 'Achats / Facture', createdBy: 'N. Idrissi', etiquettes: [], versions: [],
      createdAt: '2026-09-30T10:00:00Z', statutIndexation: 'INDEXE', permissions: ['CONSULTER', 'MODIFIER'],
      objet: 'Maintenance', dateDocument: '2026-03-12',
      ...modif,
    };
  }

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

  /** Répond à tout ce que la fiche demande au chargement ; le reste (circuit…) en 404. */
  function repondreAuChargement(d: DocumentItem, valeurs: unknown[] = []): void {
    for (const r of serveur.match(() => true)) {
      const url = r.request.url;
      if (url === `${API_BASE}/documents/d1`) r.flush(d);
      else if (url === `${API_BASE}/indexation/documents/d1/champs`) r.flush(CHAMPS);
      else if (url === `${API_BASE}/indexation/documents/d1`) r.flush(valeurs);
      else if (url.endsWith('/etiquettes')) r.flush({ content: [], total: 0, page: 0, size: 200, totalPages: 0 });
      else if (url.endsWith('/for-select')) r.flush([]);
      else r.flush(null, { status: 404, statusText: 'Introuvable' });
    }
  }

  function ouvrir(d: DocumentItem, valeurs: unknown[] = []): ComponentFixture<DocumentDetail> {
    const f = TestBed.createComponent(DocumentDetail);
    f.detectChanges();
    repondreAuChargement(d, valeurs);
    f.detectChanges();
    return f;
  }

  function saisir(f: ComponentFixture<DocumentDetail>, code: string, valeur: string): void {
    const champ = f.nativeElement.querySelector(`input[data-index="${code}"]`) as HTMLInputElement;
    champ.value = valeur;
    champ.dispatchEvent(new Event('input'));
    f.detectChanges();
  }

  it('ANO-F-005 : un document « à indexer » se reprend depuis sa fiche (PUT /indexation/documents/{id})', () => {
    const f = ouvrir(doc({ statutIndexation: 'A_INDEXER' }));
    const el: HTMLElement = f.nativeElement;
    expect(el.querySelector('.bandeau-verrou.a-indexer')?.textContent).toContain('À indexer');

    const bouton = el.querySelector('button.enregistrer-index') as HTMLButtonElement;
    expect(bouton).toBeTruthy();
    expect(bouton.disabled).toBe(true);   // Fournisseur, obligatoire, encore vide

    saisir(f, 'T_FOURN', ' ACME ');
    expect(bouton.disabled).toBe(false);
    bouton.click();

    const put = serveur.expectOne(r => r.method === 'PUT' && r.url === `${API_BASE}/indexation/documents/d1`);
    expect(put.request.body).toEqual({ valeurs: [
      { indexFieldId: 'i-fourn', valeur: 'ACME' },
      { indexFieldId: 'i-mont', valeur: null },
    ] });
    put.flush([{ indexFieldId: 'i-fourn', code: 'T_FOURN', libelle: 'Fournisseur', valeur: 'ACME' }]);
    // La fiche est rechargée : l'issue est désormais « indexé ».
    repondreAuChargement(doc({ statutIndexation: 'INDEXE' }),
      [{ indexFieldId: 'i-fourn', code: 'T_FOURN', libelle: 'Fournisseur', valeur: 'ACME' }]);
    f.detectChanges();
    expect(el.querySelector('.bandeau-verrou.a-indexer')).toBeNull();
    expect(el.querySelector('.index-liste')?.textContent).toContain('ACME');
  });

  it('ANO-F-005 : un document indexé garde ses index en lecture, avec « Saisir les index » pour les corriger', () => {
    const f = ouvrir(doc(), [{ indexFieldId: 'i-fourn', code: 'T_FOURN', libelle: 'Fournisseur', valeur: 'ACME' }]);
    const el: HTMLElement = f.nativeElement;
    expect(el.querySelector('button.enregistrer-index')).toBeNull();
    const saisie = Array.from(el.querySelectorAll('button')).find(b => b.textContent?.includes('Saisir les index'));
    expect(saisie).toBeTruthy();
    saisie!.click();
    f.detectChanges();
    expect((el.querySelector('input[data-index="T_FOURN"]') as HTMLInputElement).value).toBe('ACME');
  });

  it("ANO-F-005 : sans la permission Modifier, pas de saisie, mais l'état « à indexer » est dit", () => {
    const f = ouvrir(doc({ statutIndexation: 'A_INDEXER', permissions: ['CONSULTER'] }));
    const el: HTMLElement = f.nativeElement;
    expect(el.querySelector('.bandeau-verrou.a-indexer')?.textContent).toContain('habilitée à modifier');
    expect(el.querySelector('button.enregistrer-index')).toBeNull();
  });

  it("ANO-F-006 : l'objet et la date du document se corrigent sur la fiche (PUT /documents/{id})", () => {
    const f = ouvrir(doc());
    const el: HTMLElement = f.nativeElement;
    const objet = el.querySelector('textarea[formcontrolname="objet"]') as HTMLTextAreaElement;
    expect(objet.value).toBe('Maintenance');
    expect((el.querySelector('input[formcontrolname="dateDocument"]') as HTMLInputElement)).toBeTruthy();

    objet.value = 'Maintenance annuelle';
    objet.dispatchEvent(new Event('input'));
    f.componentInstance.form.patchValue({ dateDocument: new Date(2026, 1, 5) });
    f.detectChanges();
    const enregistrer = Array.from(el.querySelectorAll('button')).find(b => b.textContent?.trim() === 'Enregistrer')!;
    enregistrer.click();

    const put: TestRequest = serveur.expectOne(r => r.method === 'PUT' && r.url === `${API_BASE}/documents/d1`);
    expect(put.request.body.objet).toBe('Maintenance annuelle');
    expect(put.request.body.dateDocument).toBe('2026-02-05');
    put.flush(doc({ objet: 'Maintenance annuelle', dateDocument: '2026-02-05' }));
  });

  /* ---------- Tour 2 (dev5) : auteur des versions, emplacements, désignation ---------- */

  /** Ouvre la fiche en servant d'abord la liste des personnes (identités GED). */
  function ouvrirAvecPersonnes(d: DocumentItem): ComponentFixture<DocumentDetail> {
    const f = TestBed.createComponent(DocumentDetail);
    f.detectChanges();
    serveur.expectOne(r => r.url === `${API_BASE}/employes`).flush([
      { id: 'e1', firstName: 'N', lastName: 'Idrissi', fullName: 'N. Idrissi', utilisateurId: 'u-1' },
      { id: 'e2', firstName: 'K', lastName: 'El Fassi', fullName: 'K. El Fassi', utilisateurId: 'u-2' },
    ]);
    repondreAuChargement(d);
    f.detectChanges();
    return f;
  }

  it("ANO-F-012 : le tableau des versions affiche l'auteur de chaque versement", () => {
    const f = ouvrirAvecPersonnes(doc({ versions: [
      { id: 'v2', fileName: 'v2.pdf', observation: null, principale: true, sizeLabel: '1 Ko', createdAt: '2026-09-30T10:00:00Z', numero: 2, auteurId: 'u-2' },
      { id: 'v1', fileName: 'v1.pdf', observation: null, principale: false, sizeLabel: '1 Ko', createdAt: '2026-09-29T10:00:00Z', numero: 1, auteurId: 'u-1' },
    ] }));
    const el: HTMLElement = f.nativeElement;
    expect(Array.from(el.querySelectorAll('.ver-table th')).map(th => th.textContent?.trim())).toContain('Auteur');
    expect(Array.from(el.querySelectorAll('td.c-auteur')).map(td => td.textContent?.trim()))
      .toEqual(['K. El Fassi', 'N. Idrissi']);
  });

  it('ANO-F-013, ANO-F-014 : la fiche porte les emplacements, et la désignation pour un document Confidentiel', () => {
    const publicDoc = ouvrirAvecPersonnes(doc());
    expect(publicDoc.nativeElement.querySelector('app-document-emplacements')).toBeTruthy();
    expect(publicDoc.nativeElement.querySelector('app-document-designes')).toBeNull();

    const conf = ouvrirAvecPersonnes(doc({ confidentialite: 'CONFIDENTIEL' }));
    for (const r of serveur.match(() => true)) r.flush([]);   // dossiers proposés, personnes désignées
    conf.detectChanges();
    expect(conf.nativeElement.querySelector('app-document-designes')).toBeTruthy();
  });
});
