import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Route, Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from './app.routes';

@Component({ template: '' })
class Ecran {}

/**
 * Configuration réelle des routes, sans gardes ni chargement à la demande :
 * seul compte ici l'écran auquel mène une adresse.
 */
function sansGardes(liste: Route[]): Route[] {
  return liste.map(r => ({
    ...r,
    canActivate: undefined,
    canActivateChild: undefined,
    loadComponent: r.loadComponent ? () => Ecran : undefined,
    children: r.children ? sansGardes(r.children) : undefined,
  }));
}

/**
 * ANO-F-031 : le bouton « Ouvrir » d'une notification (et le lien de son
 * courriel, `<url>/#/<lien>`) navigue vers le `lien` fourni par le serveur.
 * Chaque forme de lien produite par le back doit mener à l'écran de l'objet,
 * jamais retomber sur l'accueil par la route générique.
 */
describe('Liens des notifications (ANO-F-031)', () => {
  let router: Router;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideRouter(sansGardes(routes))] });
    await RouterTestingHarness.create();
    router = TestBed.inject(Router);
  });

  // Formes produites par le serveur :
  //  - documents/<id> : CIRCUIT_OUVERT, CIRCUIT_DECISION, CIRCUIT_ANNULE
  //    (ServiceCircuits.lien) et ECHEANCE_CONSERVATION (EcheanceConservationAtteinte) ;
  //  - espaces-de-travail/<id> : ACCES_ESPACE_ATTRIBUE (EcouteurDeclencheurs).
  const CAS: [string, string][] = [
    ['documents/0192d000-0000-7000-8000-000000000001', '/televerser/0192d000-0000-7000-8000-000000000001'],
    ['espaces-de-travail/0192e000-0000-7000-8000-000000000002', '/espaces-de-travail/0192e000-0000-7000-8000-000000000002'],
  ];

  for (const [lien, attendu] of CAS) {
    it(`« ${lien.split('/')[0]}/<id> » ouvre ${attendu.split('/')[1]}/<id>`, async () => {
      await router.navigateByUrl('/' + lien);
      expect(router.url).toBe(attendu);
    });
  }

  it('la fiche d\'un document garde son adresse propre', async () => {
    await router.navigateByUrl('/televerser/d-1');
    expect(router.url).toBe('/televerser/d-1');
  });
});
