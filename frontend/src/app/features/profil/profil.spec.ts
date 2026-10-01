import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { API_BASE } from '../../core/api';
import { GED_ICONS } from '../../core/ged-icons';
import { CodeModule, ModulesService } from '../../core/modules.service';
import { Profil } from './profil.model';
import { ProfilPage } from './profil';

/**
 * « Mon profil » et le module workflow (T-088) : validations à faire, décisions
 * rendues et lien vers « Mes validations » n'existent que si le module est actif ;
 * inactif, la page ne lit pas l'historique (le serveur répondrait 404).
 */
describe('ProfilPage et le module workflow (T-088)', () => {
  let serveur: HttpTestingController;
  const inactifs = new Set<CodeModule>();

  const PROFIL: Profil = {
    id: 'e1', fullName: 'Nadia Idrissi', firstName: 'Nadia', lastName: 'Idrissi', hasUser: true,
    email: 'nidrissi@marchica.ma', compteActif: true, derniereConnexion: null,
    espacesProprietaire: [], groupesAcces: [], documentsDeposes: 4, signaturesEnAttente: 2, signaturesTraitees: 7,
  };

  beforeEach(() => {
    inactifs.clear();
    TestBed.configureTestingModule({
      imports: [ProfilPage],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
        { provide: ModulesService, useValue: { charger: () => of(undefined), actif: (c: CodeModule) => !inactifs.has(c) } },
      ],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  });

  function ouvrir(): ComponentFixture<ProfilPage> {
    const f = TestBed.createComponent(ProfilPage);
    f.detectChanges();
    serveur.expectOne(`${API_BASE}/employes/profil`).flush(PROFIL);
    serveur.match(r => r.url.includes('/auth/')).forEach(r => r.flush(null, { status: 401, statusText: 'Non' }));
    f.detectChanges();
    return f;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;

  it('module actif : lit l\'historique, montre les décisions et les compteurs de validation', () => {
    const f = ouvrir();
    serveur.expectOne(`${API_BASE}/workflow/historique`).flush([
      { id: 's1', document: 'Facture 12', libelle: 'Visa DAF', decision: 'VALIDE', le: '2026-09-30T10:00:00Z' },
    ]);
    f.detectChanges();
    expect(el(f).querySelector('.c-decisions')?.textContent).toContain('Facture 12');
    expect(el(f).querySelector('.stat-a-valider')?.getAttribute('href')).toBe('/mes-workflow');
    expect(el(f).querySelector('.stat-traitees')?.textContent).toContain('7');
  });

  it('module inactif : ni historique demandé, ni décisions, ni lien vers « Mes validations »', () => {
    inactifs.add('workflow');
    const f = ouvrir();
    serveur.expectNone(r => r.url.startsWith(`${API_BASE}/workflow`));
    expect(el(f).querySelector('.c-decisions')).toBeNull();
    expect(el(f).querySelector('.stat-a-valider')).toBeNull();
    expect(el(f).querySelector('.stat-traitees')).toBeNull();
    expect(el(f).querySelector('a[href="/mes-workflow"]')).toBeNull();
    expect(el(f).querySelector('.grille-profil')?.classList).toContain('sans-decisions');
    // Le reste de la fiche est là.
    expect(el(f).textContent).toContain('Informations professionnelles');
    expect(el(f).querySelector('.stats')?.textContent).toContain('Déposés');
  });
});
