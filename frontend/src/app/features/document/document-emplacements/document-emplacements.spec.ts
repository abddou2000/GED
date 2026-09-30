import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { API_BASE } from '../../../core/api';
import { GED_ICONS } from '../../../core/ged-icons';
import { DocumentItem } from '../document.model';
import { DocumentEmplacements } from './document-emplacements';

/** ANO-F-014 : déplacer un document vers un dossier choisi, ajouter ou retirer un rattachement. */
describe('DocumentEmplacements', () => {
  let serveur: HttpTestingController;

  function doc(modif: Partial<DocumentItem> = {}): DocumentItem {
    return {
      id: 'd1', name: 'Facture', workspace: { id: 'w1', label: 'Achats' }, typeDocument: null, fileName: 'f.pdf',
      extension: 'pdf', sizeKo: 1, sizeLabel: '1 Ko', expirationDate: null, active: true, verrouille: false,
      chemin: '', createdBy: null, etiquettes: [], versions: [], createdAt: '2026-09-30T10:00:00Z',
      permissions: ['CONSULTER', 'MODIFIER', 'DEPLACER'], rattachements: [{ id: 'w2', label: 'Comptabilité' }],
      ...modif,
    };
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [DocumentEmplacements],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  });

  async function ouvrir(d: DocumentItem, lectureSeule = false): Promise<ComponentFixture<DocumentEmplacements>> {
    const f = TestBed.createComponent(DocumentEmplacements);
    f.componentRef.setInput('doc', d);
    f.componentRef.setInput('lectureSeule', lectureSeule);
    f.detectChanges();
    serveur.expectOne(`${API_BASE}/workspaces/for-select`).flush([
      { id: 'w1', name: 'Achats' }, { id: 'w2', name: 'Comptabilité' }, { id: 'w3', name: 'Archives 2026' },
    ]);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return f;
  }

  function choisir(f: ComponentFixture<DocumentEmplacements>, nom: string, libelle: string): void {
    const select = f.nativeElement.querySelector(`select[name="${nom}"]`) as HTMLSelectElement;
    const option = Array.from(select.options).find(o => o.textContent?.trim() === libelle)!;
    select.value = option.value;
    select.dispatchEvent(new Event('change'));
    f.detectChanges();
  }

  it('déplace le document vers un dossier choisi (PATCH /documents/{id}/emplacement)', async () => {
    const f = await ouvrir(doc());
    let recharge = 0;
    f.componentInstance.modifie.subscribe(() => recharge++);
    const options = Array.from((f.nativeElement.querySelector('select[name="destination"]') as HTMLSelectElement).options)
      .map(o => o.textContent?.trim());
    expect(options).toEqual(['Déplacer vers…', 'Comptabilité', 'Archives 2026']);   // pas le dossier actuel

    choisir(f, 'destination', 'Archives 2026');
    (f.nativeElement.querySelector('button.deplacer') as HTMLButtonElement).click();
    const req = serveur.expectOne(r => r.method === 'PATCH' && r.url === `${API_BASE}/documents/d1/emplacement`);
    expect(req.request.body).toEqual({ noeudId: 'w3' });
    req.flush(doc({ workspace: { id: 'w3', label: 'Archives 2026' } }));
    expect(recharge).toBe(1);
  });

  it('rattache à un dossier de plus et retire un rattachement', async () => {
    const f = await ouvrir(doc());
    const options = Array.from((f.nativeElement.querySelector('select[name="rattachement"]') as HTMLSelectElement).options)
      .map(o => o.textContent?.trim());
    expect(options).toEqual(['Rattacher à…', 'Archives 2026']);   // ni le principal, ni un dossier déjà rattaché

    choisir(f, 'rattachement', 'Archives 2026');
    (f.nativeElement.querySelector('button.rattacher') as HTMLButtonElement).click();
    const post = serveur.expectOne(r => r.method === 'POST' && r.url === `${API_BASE}/documents/d1/rattachements`);
    expect(post.request.body).toEqual({ noeudId: 'w3' });
    post.flush(doc());

    (f.nativeElement.querySelector('button.detacher') as HTMLButtonElement).click();
    serveur.expectOne(r => r.method === 'DELETE' && r.url === `${API_BASE}/documents/d1/rattachements/w2`)
      .flush(null, { status: 204, statusText: 'No Content' });
  });

  it("sans Déplacer ni Modifier, ou document figé, n'offre aucune action", async () => {
    const lecteur = await ouvrir(doc({ permissions: ['CONSULTER'] }));
    expect(lecteur.nativeElement.querySelector('button.deplacer')).toBeNull();
    expect(lecteur.nativeElement.querySelector('button.rattacher')).toBeNull();
    expect(lecteur.nativeElement.querySelector('button.detacher')).toBeNull();
    expect(lecteur.nativeElement.textContent).toContain('Comptabilité');

    const fige = await ouvrir(doc(), true);
    expect(fige.nativeElement.querySelector('button.deplacer')).toBeNull();
    expect(fige.nativeElement.querySelector('button.rattacher')).toBeNull();
  });
});
