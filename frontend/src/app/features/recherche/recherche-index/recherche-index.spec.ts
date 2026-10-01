import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { API_BASE } from '../../../core/api';
import { GED_ICONS } from '../../../core/ged-icons';
import { Critere } from '../../indexation/indexation.model';
import { DocumentItem } from '../../document/document.model';
import { RechercheIndex } from './recherche-index';

/**
 * Recherche multicritère sur les index (ANO-F-010) et critères imposés du
 * socle : date du document, confidentialité, déposant (ANO-F-011) ; état OCR
 * signalé dans les résultats (ANO-F-017).
 */
describe('RechercheIndex', () => {
  let serveur: HttpTestingController;

  const CRITERES: Critere[] = [
    { id: 'i1', code: 'DATE_FACT', libelle: 'Date de facture', fieldType: 'DATE', options: [], groupage: false },
    { id: 'i2', code: 'MONTANT', libelle: 'Montant', fieldType: 'NOMBRE', options: [], groupage: false },
    { id: 'i3', code: 'STATUT', libelle: 'Statut', fieldType: 'LISTE', options: ['Payée', 'Impayée'], groupage: false },
    { id: 'i4', code: 'URGENT', libelle: 'Urgent', fieldType: 'BOOLEEN', options: [], groupage: false },
    { id: 'i5', code: 'FOURN', libelle: 'Fournisseur', fieldType: 'TEXTE', options: [], groupage: false },
  ];

  const DOC: DocumentItem = {
    id: 'd1', name: 'Facture ACME', workspace: { id: 'w', label: 'Achats' }, typeDocument: { id: 't', label: 'Facture' },
    fileName: 'f.pdf', extension: 'pdf', sizeKo: 1, sizeLabel: '1 Ko', expirationDate: null, active: true,
    verrouille: false, chemin: '', createdBy: 'N. Idrissi', etiquettes: [], versions: [],
    createdAt: '2026-09-30T10:00:00Z', dateDocument: '2026-03-12', statutOcr: 'EN_ATTENTE_OCR',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [RechercheIndex],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  });

  async function ouvrir(): Promise<ComponentFixture<RechercheIndex>> {
    const f = TestBed.createComponent(RechercheIndex);
    f.detectChanges();
    serveur.expectOne(`${API_BASE}/indexation/criteres`).flush(CRITERES);
    serveur.expectOne(`${API_BASE}/type-documents/for-select`).flush([{ id: 't', name: 'Facture' }]);
    serveur.expectOne(`${API_BASE}/workspaces/for-select`).flush([{ id: 'w', name: 'Achats' }]);
    serveur.expectOne(r => r.url === `${API_BASE}/employes`).flush([
      { id: 'e1', firstName: 'N', lastName: 'Idrissi', fullName: 'N. Idrissi', utilisateurId: 'u-1' },
      { id: 'e2', firstName: 'S', lastName: 'Sans', fullName: 'Sans compte', utilisateurId: null },
    ]);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return f;
  }

  function lancer(f: ComponentFixture<RechercheIndex>): void {
    (f.nativeElement.querySelector('button.lancer') as HTMLButtonElement).click();
    f.detectChanges();
  }

  it('ANO-F-010 : génère un critère par index « recherche », avec plages pour les dates et les nombres', async () => {
    const f = await ouvrir();
    const el: HTMLElement = f.nativeElement;
    expect(el.querySelector('input[type="date"][name="de-DATE_FACT"]')).toBeTruthy();
    expect(el.querySelector('input[type="date"][name="a-DATE_FACT"]')).toBeTruthy();
    expect(el.querySelector('input[type="number"][name="de-MONTANT"]')).toBeTruthy();
    expect(el.querySelector('input[type="number"][name="a-MONTANT"]')).toBeTruthy();
    const statut = el.querySelector('select[name="v-STATUT"]') as HTMLSelectElement;
    expect(Array.from(statut.options).map(o => o.textContent?.trim())).toEqual(['Toutes', 'Payée', 'Impayée']);
    expect(el.querySelector('select[name="v-URGENT"]')).toBeTruthy();
    expect(el.querySelector('input[name="v-FOURN"]')).toBeTruthy();
    // Le déposant se choisit parmi les personnes dotées d'une identité GED.
    const deposant = el.querySelector('select[name="deposant"]') as HTMLSelectElement;
    expect(Array.from(deposant.options).map(o => o.textContent?.trim())).toEqual(['Tous', 'N. Idrissi']);
  });

  it('ANO-F-010, ANO-F-011 : envoie les seuls critères renseignés, en ET, à POST /documents/recherche', async () => {
    const f = await ouvrir();
    const el: HTMLElement = f.nativeElement;
    // Saisie réelle dans un contrôle généré…
    const fournisseur = el.querySelector('input[name="v-FOURN"]') as HTMLInputElement;
    fournisseur.value = ' acme ';
    fournisseur.dispatchEvent(new Event('input'));
    // … et les autres par le modèle de l'écran.
    const c = f.componentInstance;
    c.saisie('DATE_FACT').de = '2026-01-01';
    c.saisie('DATE_FACT').a = '2026-06-30';
    c.saisie('MONTANT').de = '100';
    c.saisie('STATUT').valeur = 'Payée';
    c.socle.dateDocumentDu = '2026-03-01';
    c.socle.dateDocumentAu = '2026-03-31';
    c.socle.confidentialite = 'PRIVE';
    c.socle.deposantUtilisateurId = 'u-1';
    c.socle.typeDocumentId = 't';
    f.detectChanges();
    lancer(f);

    const req = serveur.expectOne(r => r.method === 'POST' && r.url === `${API_BASE}/documents/recherche`);
    expect(req.request.params.get('sortBy')).toBe('dateDocument');
    expect(req.request.params.get('sortDir')).toBe('desc');
    expect(req.request.body).toEqual({
      typeDocumentId: 't',
      criteres: [
        { code: 'DATE_FACT', de: '2026-01-01', a: '2026-06-30' },
        { code: 'MONTANT', de: '100', a: null },
        { code: 'STATUT', valeur: 'Payée' },
        { code: 'FOURN', valeur: 'acme' },
      ],
      dateDocumentDu: '2026-03-01',
      dateDocumentAu: '2026-03-31',
      confidentialite: 'PRIVE',
      deposantUtilisateurId: 'u-1',
      page: 0,
      size: 50,
    });
    req.flush({ content: [DOC], total: 1, page: 0, size: 50, totalPages: 1 });
    f.detectChanges();
    expect(el.querySelector('.total')?.textContent).toContain('1 document');
    expect((el.querySelector('a.nom') as HTMLAnchorElement).getAttribute('href')).toBe('/televerser/d1');
    // ANO-F-017 : l'état OCR est signalé dans les résultats.
    expect(el.querySelector('.badge-ocr')?.textContent).toContain('OCR en attente');
  });

  it('ANO-F-010 : les résultats se trient (nom, date du document, dépôt) et se paginent côté serveur', async () => {
    const f = await ouvrir();
    lancer(f);
    serveur.expectOne(r => r.url === `${API_BASE}/documents/recherche`)
      .flush({ content: [DOC], total: 45, page: 0, size: 20, totalPages: 3 });
    f.detectChanges();

    const entete = Array.from(f.nativeElement.querySelectorAll('th') as NodeListOf<HTMLElement>)
      .find(th => th.textContent?.includes('Nom'))!;
    ((entete.querySelector('.mat-sort-header-container') as HTMLElement) ?? entete).click();
    f.detectChanges();
    const triee = serveur.expectOne(r => r.url === `${API_BASE}/documents/recherche`);
    expect(triee.request.params.get('sortBy')).toBe('name');
    expect(triee.request.params.get('sortDir')).toBe('asc');
    triee.flush({ content: [DOC], total: 45, page: 0, size: 20, totalPages: 3 });
    f.detectChanges();

    f.componentInstance.pagination({ pageIndex: 2, pageSize: 20, length: 45 });
    const page = serveur.expectOne(r => r.url === `${API_BASE}/documents/recherche`);
    expect(page.request.body.page).toBe(2);
    expect(page.request.params.get('sortBy')).toBe('name');
    page.flush({ content: [], total: 45, page: 2, size: 20, totalPages: 3 });
  });

  it('T-050 : 50 résultats par page par défaut, sélecteur plafonné à 200', async () => {
    const f = await ouvrir();
    lancer(f);
    const req = serveur.expectOne(r => r.url === `${API_BASE}/documents/recherche`);
    expect(req.request.body.size).toBe(50);
    req.flush({ content: [DOC], total: 300, page: 0, size: 50, totalPages: 6 });
    f.detectChanges();
    expect(Math.max(...f.componentInstance.taillesPage)).toBe(200);
    f.componentInstance.pagination({ pageIndex: 0, pageSize: 200, length: 300 });
    const grande = serveur.expectOne(r => r.url === `${API_BASE}/documents/recherche`);
    expect(grande.request.body.size).toBe(200);
    grande.flush({ content: [DOC], total: 300, page: 0, size: 200, totalPages: 2 });
  });

  it('signale discrètement un critère que le serveur a ignoré (GED-Champs-Ignores)', async () => {
    const f = await ouvrir();
    lancer(f);
    serveur.expectOne(r => r.url === `${API_BASE}/documents/recherche`).flush(
      { content: [DOC], total: 1, page: 0, size: 50, totalPages: 1 },
      { headers: { 'GED-Champs-Ignores': 'criteres[0].valeurr' } });
    f.detectChanges();
    const el: HTMLElement = f.nativeElement;
    expect(el.querySelector('.champs-ignores')?.textContent).toContain('Critère non appliqué : criteres[0].valeurr');
    expect(el.querySelector('a.nom')).toBeTruthy();

    lancer(f);
    serveur.expectOne(r => r.url === `${API_BASE}/documents/recherche`)
      .flush({ content: [DOC], total: 1, page: 0, size: 50, totalPages: 1 });
    f.detectChanges();
    expect(el.querySelector('.champs-ignores')).toBeNull();
  });

  it("refuse une plage incohérente sans appeler l'API", async () => {
    const f = await ouvrir();
    f.componentInstance.saisie('MONTANT').de = '500';
    f.componentInstance.saisie('MONTANT').a = '100';
    lancer(f);
    serveur.expectNone(r => r.url === `${API_BASE}/documents/recherche`);
    expect(f.nativeElement.querySelector('.erreur')?.textContent).toContain('Montant');
  });
});
