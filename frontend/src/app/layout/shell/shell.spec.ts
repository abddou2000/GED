import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { Shell } from './shell';
import { AuthService } from '../../core/auth.service';
import { CodeModule, ModulesService } from '../../core/modules.service';
import { GED_ICONS } from '../../core/ged-icons';

const TOUS_MODULES: CodeModule[] = ['ocr', 'workflow', 'cycledevie', 'export', 'notifications', 'integration'];

/** Coque rendue pour un administrateur complet, avec les modules donnés inactifs. */
function rendre(inactifs: CodeModule[]): { liens: string[]; serveur: HttpTestingController } {
  TestBed.configureTestingModule({
    imports: [Shell],
    providers: [
      provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
      { provide: ModulesService, useValue: { charger: () => of(undefined), actif: (c: CodeModule) => !inactifs.includes(c) } },
    ],
  });
  const icones = TestBed.inject(MatIconRegistry);
  const assainisseur = TestBed.inject(DomSanitizer);
  for (const { name, svg } of GED_ICONS) icones.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
  TestBed.inject(AuthService).utilisateur.set({
    id: 'u-1', identifiant: 'sbennani', employeId: 'e-1', fullName: 'Sara Bennani', email: null,
    direction: null, roles: ['ADMINISTRATEUR'],
    permissions: ['GERER_REFERENTIELS', 'GERER_ROLES_HABILITATIONS', 'GERER_CLES_API', 'CONSULTER_AUDIT',
      'SUPERVISER_TRAITEMENTS'],
  });
  const f = TestBed.createComponent(Shell);
  f.detectChanges();
  const liens = Array.from((f.nativeElement as HTMLElement).querySelectorAll('a[href]'))
    .map(a => a.getAttribute('href') ?? '');
  return { liens, serveur: TestBed.inject(HttpTestingController) };
}

/** T-088 (critère 2) : les menus d'un module métier désactivé sont masqués. */
describe('Menus des modules métier (T-088)', () => {
  const MENUS_MODULES = ['/mes-workflow', '/regles-de-workflow', '/recherche', '/traitements-ocr',
    '/mes-exports', '/cles-api', '/notifications'];

  it('affiche tous les menus quand les modules sont actifs', () => {
    const { liens } = rendre([]);
    for (const m of MENUS_MODULES) expect(liens).toContain(m);
  });

  it('masque les menus des modules inactifs et garde ceux du socle', () => {
    const { liens, serveur } = rendre(TOUS_MODULES);
    for (const m of MENUS_MODULES) expect(liens).not.toContain(m);
    for (const m of ['/accueil', '/espaces-de-travail', '/televerser', '/index', '/journal-audit',
      '/administration/habilitations']) {
      expect(liens).toContain(m);
    }
    // Ni compteur « à traiter » ni pastille de notifications : le serveur répondrait 404.
    expect(serveur.match(r => r.url.includes('/workflow/') || r.url.includes('/notifications'))).toEqual([]);
  });

  it('ne masque que le module concerné', () => {
    const { liens } = rendre(['export']);
    expect(liens).not.toContain('/mes-exports');
    expect(liens).toContain('/mes-workflow');
    expect(liens).toContain('/recherche');
  });
});
