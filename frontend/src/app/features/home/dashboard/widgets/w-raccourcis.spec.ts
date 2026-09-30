import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { AuthService, Identite } from '../../../../core/auth.service';
import { GED_ICONS } from '../../../../core/ged-icons';
import { WRaccourcis } from './w-raccourcis';

/** ANO-F-004 : l'accueil ne propose que ce que le profil permet. */
describe('WRaccourcis', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [WRaccourcis],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
  });

  function liensPour(roles: string[], permissions: string[]): { texte: string; href: string }[] {
    const identite: Identite = {
      id: 'u', identifiant: 'nidrissi', employeId: 'e', fullName: 'N. Idrissi', email: null, direction: null,
      roles, permissions,
    };
    TestBed.inject(AuthService).utilisateur.set(identite);
    const f = TestBed.createComponent(WRaccourcis);
    f.detectChanges();
    return Array.from(f.nativeElement.querySelectorAll('a.rac') as NodeListOf<HTMLAnchorElement>)
      .map(a => ({ texte: a.textContent ?? '', href: a.getAttribute('href') ?? '' }));
  }

  it("n'offre à un utilisateur standard ni la création d'espace ni le référentiel des index", () => {
    const liens = liensPour(['UTILISATEUR_STANDARD'], ['CONSULTER', 'DEPOSER']);
    const hrefs = liens.map(l => l.href);
    expect(hrefs).toEqual(['/televerser', '/mes-workflow', '/recherche-par-index']);
    expect(liens.some(l => l.texte.includes('Créer un espace'))).toBe(false);
    expect(hrefs).not.toContain('/index');
  });

  it("propose la création d'espace à qui gère les espaces", () => {
    const hrefs = liensPour(['ADMINISTRATEUR'], ['CONSULTER', 'DEPOSER', 'GERER_ESPACES']).map(l => l.href);
    expect(hrefs).toContain('/espaces-de-travail');
  });

  it('ANO-F-010 : « Rechercher par index » mène à la recherche multicritère sur les index', () => {
    const lien = liensPour(['UTILISATEUR_STANDARD'], ['CONSULTER']).find(l => l.texte.includes('Rechercher par index'));
    expect(lien?.href).toBe('/recherche-par-index');
  });

  it('masque le dépôt à qui ne peut pas déposer', () => {
    const hrefs = liensPour(['LECTEUR'], ['CONSULTER']).map(l => l.href);
    expect(hrefs).toEqual(['/mes-workflow', '/recherche-par-index']);
  });
});
