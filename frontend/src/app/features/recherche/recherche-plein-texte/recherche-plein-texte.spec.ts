import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { API_BASE } from '../../../core/api';
import { GED_ICONS } from '../../../core/ged-icons';
import { PageResultats } from '../recherche.model';
import { RecherchePleinTexte } from './recherche-plein-texte';

/**
 * Recherche plein texte : pagination contractuelle (T-050, DAT §5.3.2 : 50 par
 * défaut, 200 au plus) et critères ignorés par le serveur signalés à l'écran
 * (en-tête GED-Champs-Ignores).
 */
describe('RecherchePleinTexte', () => {
  let serveur: HttpTestingController;
  const URL = `${API_BASE}/recherche/plein-texte`;

  const PAGE: PageResultats = {
    resultats: [{
      documentId: 'd1', versionId: 'v1', pertinence: 1, nom: 'Contrat ACME', typeDocument: 'Contrat',
      espace: 'Achats', deposeLe: '2026-09-30T10:00:00Z', extrait: [{ texte: 'acme', surligne: true }],
    }],
    total: 1, page: 0, taille: 50,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [RecherchePleinTexte],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  });

  afterEach(() => serveur.verify());

  function ouvrir(): ComponentFixture<RecherchePleinTexte> {
    const f = TestBed.createComponent(RecherchePleinTexte);
    f.detectChanges();
    serveur.expectOne(r => r.url === `${API_BASE}/type-documents`).flush({ content: [], total: 0 });
    serveur.expectOne(r => r.url === `${API_BASE}/workspaces`).flush({ content: [], total: 0 });
    f.detectChanges();
    return f;
  }

  function chercher(f: ComponentFixture<RecherchePleinTexte>, q = 'acme'): void {
    f.componentInstance.criteres.q = q;
    f.componentInstance.lancer();
    f.detectChanges();
  }

  it('T-050 : demande 50 résultats par page par défaut, sélecteur plafonné à 200', () => {
    const f = ouvrir();
    chercher(f);
    const req = serveur.expectOne(r => r.url === URL);
    expect(req.request.params.get('taille')).toBe('50');
    expect(req.request.params.get('page')).toBe('0');
    req.flush(PAGE);
    f.detectChanges();

    expect(f.componentInstance.taillesPage).toContain(50);
    expect(Math.max(...f.componentInstance.taillesPage)).toBe(200);
    // La taille choisie dans le sélecteur est celle envoyée.
    f.componentInstance.pagination({ pageIndex: 1, pageSize: 200, length: 1 });
    const suivante = serveur.expectOne(r => r.url === URL);
    expect(suivante.request.params.get('taille')).toBe('200');
    expect(suivante.request.params.get('page')).toBe('1');
    suivante.flush({ ...PAGE, resultats: [], page: 1, taille: 200 });
  });

  it('signale discrètement les critères que le serveur a ignorés (GED-Champs-Ignores)', () => {
    const f = ouvrir();
    chercher(f);
    serveur.expectOne(r => r.url === URL).flush(PAGE, { headers: { 'GED-Champs-Ignores': 'canal' } });
    f.detectChanges();
    const el: HTMLElement = f.nativeElement;
    const avis = el.querySelector('.champs-ignores');
    expect(avis?.textContent).toContain('Critère non appliqué : canal');
    expect(avis?.getAttribute('role')).toBe('status');
    // Les résultats restent affichés.
    expect(el.querySelector('a.nom')?.textContent).toContain('Contrat ACME');

    // Plusieurs noms, encodés en pourcentage par le serveur : décodés.
    chercher(f);
    serveur.expectOne(r => r.url === URL)
      .flush(PAGE, { headers: { 'GED-Champs-Ignores': 'canal, d%C3%A9pos%C3%A9' } });
    f.detectChanges();
    expect(el.querySelector('.champs-ignores')?.textContent).toContain('Critères non appliqués : canal, déposé');

    // Sans en-tête, l'avertissement disparaît.
    chercher(f);
    serveur.expectOne(r => r.url === URL).flush(PAGE);
    f.detectChanges();
    expect(el.querySelector('.champs-ignores')).toBeNull();
  });
});
