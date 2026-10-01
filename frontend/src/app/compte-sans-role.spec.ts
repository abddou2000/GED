import { EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRouteSnapshot, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { AuthService, Identite } from './core/auth.service';
import { roleGuard } from './core/auth.guard';
import { GED_ICONS } from './core/ged-icons';
import { Dashboard } from './features/home/dashboard/dashboard';
import { Shell } from './layout/shell/shell';

/**
 * P-04 (dossier technique §3.4.2, revue client D1) : une identité provisionnée
 * sans rôle voit une page d'accueil vide qui lui dit pourquoi, et rien d'autre.
 * Le serveur lui refuse tout sauf `/auth/me` (403) : l'écran ne doit donc ni
 * proposer un menu qui la renverrait à l'accueil, ni appeler le serveur.
 */
describe('Compte sans rôle : page d\'accueil vide (P-04)', () => {
  let serveur: HttpTestingController;

  function sansRole(roles: string[] = []): Identite {
    return {
      id: 'u-9', identifiant: 'ybenali', employeId: 'e-9', fullName: 'Youssef Benali', email: 'ybenali@marchica.ma',
      direction: null, roles, permissions: [],
    };
  }

  beforeEach(() => {
    try { localStorage.clear(); } catch { /* stockage indisponible */ }
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    const registre = TestBed.inject(MatIconRegistry);
    const assainisseur = TestBed.inject(DomSanitizer);
    for (const { name, svg } of GED_ICONS) registre.addSvgIconLiteral(name, assainisseur.bypassSecurityTrustHtml(svg));
    serveur = TestBed.inject(HttpTestingController);
  });

  it('l\'accueil affiche le message d\'attente d\'un rôle, sans encart ni appel au serveur', () => {
    TestBed.inject(AuthService).utilisateur.set(sansRole());
    const f = TestBed.createComponent(Dashboard);
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    const vide = el.querySelector('.accueil-vide');
    expect(vide).not.toBeNull();
    expect(vide!.getAttribute('role')).toBe('status');
    expect(vide!.textContent).toContain('Bienvenue Youssef');
    expect(vide!.textContent).toContain('aucun rôle ne vous est encore attribué');
    expect(vide!.textContent).toContain('L\'Administrateur de la GED');
    expect(el.querySelector('.hello')).toBeNull();
    expect(serveur.match(() => true).map(r => r.request.url)).toEqual([]);
  });

  it('avec un rôle, l\'accueil habituel revient (rôles conservés à la réactivation)', () => {
    TestBed.inject(AuthService).utilisateur.set(sansRole(['UTILISATEUR']));
    const f = TestBed.createComponent(Dashboard);
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).querySelector('.accueil-vide')).toBeNull();
    expect((f.nativeElement as HTMLElement).querySelector('.hello')).not.toBeNull();
  });

  it('tout autre écran ramène à l\'accueil', () => {
    TestBed.inject(AuthService).utilisateur.set(sansRole());
    const r = runInInjectionContext(TestBed.inject(EnvironmentInjector), () =>
      roleGuard({} as ActivatedRouteSnapshot, { url: '/profil' } as RouterStateSnapshot));
    expect(r).toBeInstanceOf(UrlTree);
    expect((r as UrlTree).toString()).toBe('/accueil');
  });

  it('la coque ne propose que l\'accueil et la déconnexion, et n\'interroge pas le serveur', async () => {
    TestBed.inject(AuthService).utilisateur.set(sansRole());
    const f = TestBed.createComponent(Shell);
    f.detectChanges();
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    const liens = Array.from(el.querySelectorAll('a[href]')).map(a => a.getAttribute('href'));
    expect(liens.filter(h => h !== '/accueil')).toEqual([]);
    expect(el.querySelector('.cloche')).toBeNull();
    // Aucun appel refusé d'avance : état des modules, pastille, compteur « à traiter ».
    expect(serveur.match(() => true).map(r => r.request.url)).toEqual([]);

    // Carte de compte : « Se déconnecter », pas « Mon profil » (écran fermé sans rôle).
    (el.querySelector('.acc-btn') as HTMLButtonElement).click();
    f.detectChanges();
    const items = Array.from(document.querySelectorAll('.carte-compte [mat-menu-item]'))
      .map(b => (b.textContent ?? '').trim());
    expect(items).toContain('Se déconnecter');
    expect(items).not.toContain('Mon profil');
  });
});
