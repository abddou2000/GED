import { EnvironmentInjector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import {
  ActivatedRouteSnapshot, CanActivateFn, Route, RouterStateSnapshot, UrlTree, provideRouter,
} from '@angular/router';
import { Observable, firstValueFrom, isObservable } from 'rxjs';
import { routes } from './app.routes';
import { AuthService, Identite } from './core/auth.service';

/** Route de la configuration par son chemin complet (segments des parents compris). */
function routePour(chemin: string, liste: Route[] = routes, prefixe = ''): Route | undefined {
  for (const r of liste) {
    const complet = [prefixe, r.path ?? ''].filter(Boolean).join('/');
    if (complet === chemin && r.loadComponent) return r;
    if (r.children) {
      const trouvee = routePour(chemin, r.children, complet);
      if (trouvee) return trouvee;
    }
  }
  return undefined;
}

function identite(permissions: string[]): Identite {
  return {
    id: 'u-1', identifiant: 'nidrissi', employeId: 'e-1', fullName: 'N. Idrissi', email: null,
    direction: null, roles: ['UTILISATEUR'], permissions,
  };
}

/** Exécute les gardes `canActivate` d'une route ; `true` si toutes laissent passer. */
async function franchir(chemin: string): Promise<true | UrlTree> {
  const route = routePour(chemin);
  expect(route, `route ${chemin} introuvable`).toBeDefined();
  const gardes = (route!.canActivate ?? []) as CanActivateFn[];
  const injecteur = TestBed.inject(EnvironmentInjector);
  for (const garde of gardes) {
    let r = runInInjectionContext(injecteur, () =>
      garde({} as ActivatedRouteSnapshot, { url: '/' + chemin } as RouterStateSnapshot));
    if (isObservable(r)) r = await firstValueFrom(r as Observable<boolean | UrlTree>);
    else r = await r;
    if (r !== true) return r as UrlTree;
  }
  return true;
}

/**
 * ANO-F-003 : les écrans d'administration ne s'ouvrent pas par leur adresse sans
 * la permission qui les gouverne (même mécanisme que `administration/*`).
 */
describe('Gardes des écrans d\'administration (ANO-F-003)', () => {
  const ECRANS: [string, string][] = [
    ['index', 'GERER_REFERENTIELS'],
    ['plan-indexation', 'GERER_REFERENTIELS'],
    ['plan-indexation/create', 'GERER_REFERENTIELS'],
    ['plan-indexation/:id/edit', 'GERER_REFERENTIELS'],
    ['type-de-document', 'GERER_REFERENTIELS'],
    ['type-de-document/:id', 'GERER_REFERENTIELS'],
    ['etiquette', 'GERER_REFERENTIELS'],
    ['regles-de-workflow', 'GERER_REFERENTIELS'],
    ['groupe-d-acces', 'GERER_ROLES_HABILITATIONS'],
    ['groupe-d-acces/:id', 'GERER_ROLES_HABILITATIONS'],
    ['journal-audit', 'CONSULTER_AUDIT'],
    ['cles-api', 'GERER_CLES_API'],
  ];

  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    auth = TestBed.inject(AuthService);
  });

  for (const [chemin, permission] of ECRANS) {
    it(`#/${chemin} renvoie à l'accueil sans ${permission}`, async () => {
      auth.utilisateur.set(identite(['CONSULTER', 'DEPOSER']));
      const r = await franchir(chemin);
      expect(r).toBeInstanceOf(UrlTree);
      expect((r as UrlTree).toString()).toBe('/accueil');
    });

    it(`#/${chemin} s'ouvre avec ${permission}`, async () => {
      auth.utilisateur.set(identite([permission]));
      expect(await franchir(chemin)).toBe(true);
    });
  }

  it('les écrans de l\'utilisateur restent ouverts sans permission d\'administration', async () => {
    auth.utilisateur.set(identite(['CONSULTER']));
    for (const chemin of ['espaces-de-travail', 'televerser', 'profil']) {
      expect(await franchir(chemin)).toBe(true);
    }
  });
});
