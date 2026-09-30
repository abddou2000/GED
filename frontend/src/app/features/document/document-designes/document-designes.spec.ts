import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { API_BASE } from '../../../core/api';
import { GED_ICONS } from '../../../core/ged-icons';
import { DocumentDesignes } from './document-designes';

/** ANO-F-013 : désigner les personnes autorisées à lire un document Confidentiel. */
describe('DocumentDesignes', () => {
  let serveur: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [DocumentDesignes],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  });

  async function ouvrir(lectureSeule = false): Promise<ComponentFixture<DocumentDesignes>> {
    const f = TestBed.createComponent(DocumentDesignes);
    f.componentRef.setInput('documentId', 'd1');
    f.componentRef.setInput('lectureSeule', lectureSeule);
    f.detectChanges();
    serveur.expectOne(`${API_BASE}/documents/d1/designes`).flush([{ id: 'u-1', label: 'N. Idrissi' }]);
    serveur.expectOne(r => r.url === `${API_BASE}/employes`).flush([
      { id: 'e1', firstName: 'N', lastName: 'Idrissi', fullName: 'N. Idrissi', utilisateurId: 'u-1' },
      { id: 'e2', firstName: 'K', lastName: 'El Fassi', fullName: 'K. El Fassi', utilisateurId: 'u-2' },
      { id: 'e3', firstName: 'S', lastName: 'Sans', fullName: 'Sans compte', utilisateurId: null },
    ]);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    return f;
  }

  it('liste les personnes désignées et en désigne une nouvelle (POST /documents/{id}/designes)', async () => {
    const f = await ouvrir();
    const el: HTMLElement = f.nativeElement;
    expect(el.querySelector('.liste')?.textContent).toContain('N. Idrissi');
    // Ne sont proposées que les personnes dotées d'une identité GED et pas encore désignées.
    const select = el.querySelector('select[name="personne"]') as HTMLSelectElement;
    expect(Array.from(select.options).map(o => o.textContent?.trim())).toEqual(['Choisir une personne…', 'K. El Fassi']);

    select.value = select.options[1].value;
    select.dispatchEvent(new Event('change'));
    f.detectChanges();
    (el.querySelector('button.designer') as HTMLButtonElement).click();
    const post = serveur.expectOne(r => r.method === 'POST' && r.url === `${API_BASE}/documents/d1/designes`);
    expect(post.request.body).toEqual({ utilisateurId: 'u-2' });
    post.flush([{ id: 'u-1', label: 'N. Idrissi' }, { id: 'u-2', label: 'K. El Fassi' }]);
    f.detectChanges();
    expect(el.querySelectorAll('.liste li').length).toBe(2);
  });

  it('retire une désignation (DELETE /documents/{id}/designes/{utilisateur})', async () => {
    const f = await ouvrir();
    (f.nativeElement.querySelector('button.retirer') as HTMLButtonElement).click();
    serveur.expectOne(r => r.method === 'DELETE' && r.url === `${API_BASE}/documents/d1/designes/u-1`)
      .flush(null, { status: 204, statusText: 'No Content' });
    f.detectChanges();
    expect(f.nativeElement.querySelector('.liste')).toBeNull();
    expect(f.nativeElement.textContent).toContain('Aucune personne désignée');
  });

  it('document verrouillé ou archivé : liste seule, sans action', async () => {
    const f = await ouvrir(true);
    expect(f.nativeElement.querySelector('button.retirer')).toBeNull();
    expect(f.nativeElement.querySelector('button.designer')).toBeNull();
  });
});
