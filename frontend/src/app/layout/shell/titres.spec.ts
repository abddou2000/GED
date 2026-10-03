import { Route } from '@angular/router';
import { routes } from '../../app.routes';
import { titrePage } from './titres';

/** Chemins complets des écrans de l'application (routes qui chargent un composant). */
function ecrans(liste: Route[], prefixe = ''): string[] {
  const chemins: string[] = [];
  for (const r of liste) {
    const complet = [prefixe, r.path ?? ''].filter(Boolean).join('/');
    if (r.loadComponent) chemins.push(complet);
    if (r.children) chemins.push(...ecrans(r.children, complet));
  }
  return chemins;
}

/** ANO-F-035 : le titre de la barre supérieure suit l'écran affiché, pour toutes les routes. */
describe('Titre de la barre supérieure (ANO-F-035)', () => {
  it('chaque écran de l\'application a son titre (seul l\'accueil s\'intitule « Accueil »)', () => {
    const sansTitre = ecrans(routes)
      .filter(c => c !== 'login' && c !== 'accueil' && c !== '')
      .map(c => '/' + c.replace(/:[^/]+/g, '0192d000'))
      .filter(url => titrePage(url) === 'Accueil');
    expect(sansTitre).toEqual([]);
  });

  it('écrans relevés en démonstration', () => {
    expect(titrePage('/administration/habilitations')).toBe('Habilitations');
    expect(titrePage('/administration/roles')).toBe('Rôles');
    expect(titrePage('/administration/droits-effectifs')).toBe('Droits effectifs');
    expect(titrePage('/administration/sessions')).toBe('Sessions');
    expect(titrePage('/recherche')).toBe('Recherche plein texte');
    expect(titrePage('/recherche-par-index')).toBe('Recherche par index');
    expect(titrePage('/traitements-ocr')).toBe('Traitements OCR');
    expect(titrePage('/mes-exports')).toBe('Mes exports');
  });

  it('le chemin le plus précis l\'emporte ; paramètres et fragment ignorés', () => {
    expect(titrePage('/type-de-document/retypage')).toBe('Re-typologie en lot');
    expect(titrePage('/type-de-document/0192d000')).toBe('Type de document');
    expect(titrePage('/televerser/0192d000?onglet=versions')).toBe('Documents déposés');
    expect(titrePage('/televerser?archive=1')).toBe('Documents déposés');
    expect(titrePage('/')).toBe('Accueil');
    expect(titrePage('/inconnu')).toBe('Accueil');
  });
});
