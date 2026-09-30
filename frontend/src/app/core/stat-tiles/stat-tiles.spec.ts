import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { API_BASE } from '../api';
import { AuthService, Identite } from '../auth.service';
import { GED_ICONS } from '../ged-icons';
import { StatTiles } from './stat-tiles';

/** ANO-F-004 : la tuile « Groupes d'accès » suit la permission du menu. */
describe('StatTiles', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [StatTiles],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
  });

  function hrefsPour(permissions: string[]): string[] {
    const identite: Identite = {
      id: 'u', identifiant: 'x', employeId: 'e', fullName: 'X', email: null, direction: null,
      roles: ['R'], permissions,
    };
    TestBed.inject(AuthService).utilisateur.set(identite);
    const f = TestBed.createComponent(StatTiles);
    f.detectChanges();
    TestBed.inject(HttpTestingController).expectOne(`${API_BASE}/stats/overview`)
      .flush({ workspaces: 1, documents: 2, pendingSignatures: 3, accessGroups: 4 });
    f.detectChanges();
    return Array.from(f.nativeElement.querySelectorAll('a.tile') as NodeListOf<HTMLAnchorElement>)
      .map(a => a.getAttribute('href') ?? '');
  }

  it("n'affiche pas la tuile « Groupes d'accès » à un utilisateur standard", () => {
    expect(hrefsPour(['CONSULTER', 'DEPOSER'])).toEqual(['/espaces-de-travail', '/televerser', '/mes-workflow']);
  });

  it("l'affiche à l'administrateur des droits", () => {
    expect(hrefsPour(['GERER_ROLES_HABILITATIONS'])).toContain('/groupe-d-acces');
  });
});
